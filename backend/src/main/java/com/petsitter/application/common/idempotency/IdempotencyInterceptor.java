package com.petsitter.application.common.idempotency;

import com.petsitter.application.exception.ApiErrors;
import com.petsitter.application.exception.ErrorResponse;
import com.petsitter.application.exception.RequestValidationException;
import com.petsitter.infrastructure.security.TokenContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.WebUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 段 0（SD-GLOBAL-SPEC §2.4、SD-ORDER §3.6.2）：handler mapping 之後、{@code @PreAuthorize} 之前執行。
 * <ol>
 *   <li>handler 沒有 {@link Idempotent} 或未帶 {@code Idempotency-Key} → 不處理</li>
 *   <li>{@code idempotency.bodyTooLarge} → 400 {@code VALIDATION_FAILED}</li>
 *   <li>有 body 而 Content-Type 既不是 JSON 也不是 multipart → 400 {@code VALIDATION_FAILED}（不讀 body）</li>
 *   <li>未登入（401 已由 security filter 先回；permitAll 路徑留給 Service 的 G1）或 key 格式不符（留給第 6 步）→ 不建立 context</li>
 *   <li>計算 hash、建立 {@link IdempotencyContext}；{@code COMPLETED}、未過期、同 hash → 回放第一次狀態碼與 body（第 2 步）</li>
 * </ol>
 * 其餘情況不在此回錯。{@link #afterCompletion} 對已認領但未成功完成的請求執行失敗段。
 */
@Component
@RequiredArgsConstructor
public class IdempotencyInterceptor implements HandlerInterceptor {

    private static final int MAX_KEY_LENGTH = 128;

    private final IdempotencyService idempotencyService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!(handler instanceof HandlerMethod method) || !method.hasMethodAnnotation(Idempotent.class)) {
            return true;
        }
        String key = request.getHeader(IdempotencyBodyCachingFilter.HEADER);
        if (key == null) {
            return true;
        }
        if (Boolean.TRUE.equals(request.getAttribute(IdempotencyBodyCachingFilter.BODY_TOO_LARGE_ATTRIBUTE))) {
            throw new RequestValidationException("body",
                    Map.of("maxBytes", IdempotencyBodyCachingFilter.MAX_BODY_BYTES));
        }
        String contentType = request.getContentType();
        boolean json = IdempotencyBodyCachingFilter.isJson(contentType);
        boolean multipart = IdempotencyBodyCachingFilter.isMultipart(contentType);
        if (!json && !multipart && hasBody(request)) {
            throw new RequestValidationException("Content-Type");
        }

        Optional<UUID> userId = TokenContext.tryGetUserId();
        if (userId.isEmpty() || !isValidKey(key)) {
            return true;
        }

        String template = routeTemplate(request);
        Map<String, String> pathVariables = pathVariables(request);
        String hash;
        if (multipart) {
            hash = multipartHash(request, template, pathVariables);
        } else if (json) {
            IdempotencyBodyCachingFilter.CachedBodyRequest cached =
                    WebUtils.getNativeRequest(request, IdempotencyBodyCachingFilter.CachedBodyRequest.class);
            if (cached == null) {
                throw new RuntimeException("IdempotencyBodyCachingFilter is not registered");
            }
            hash = IdempotencyRequestHasher.hashJson(template, pathVariables, cached.body());
        } else {
            hash = IdempotencyRequestHasher.hashJson(template, pathVariables, new byte[0]);
        }

        IdempotencyContext ctx = new IdempotencyContext(userId.get(), request.getMethod() + " " + template, key, hash);
        request.setAttribute(IdempotencyService.STATE_ATTRIBUTE, new IdempotencyService.RequestState(ctx));

        Optional<IdempotencyStoredResponse> replay = idempotencyService.findReplay(ctx);
        if (replay.isEmpty()) {
            return true;
        }
        IdempotencyStoredResponse stored = replay.get();
        response.setStatus(stored.status());
        if (stored.bodyJson() != null) {
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getOutputStream().write(stored.bodyJson().getBytes(StandardCharsets.UTF_8));
        }
        return false;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                Exception ex) {
        Object state = request.getAttribute(IdempotencyService.STATE_ATTRIBUTE);
        if (!(state instanceof IdempotencyService.RequestState requestState) || requestState.claim == null) {
            return;
        }
        Object error = request.getAttribute(ApiErrors.ERROR_RESPONSE_ATTRIBUTE);
        idempotencyService.onRequestCompleted(requestState, response.getStatus(),
                error instanceof ErrorResponse e ? e : null, ex != null);
    }

    /** 1–128 個可見 ASCII 字元（SD-ORDER §6.1）。 */
    static boolean isValidKey(String key) {
        if (key.isEmpty() || key.length() > MAX_KEY_LENGTH) {
            return false;
        }
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c < 0x21 || c > 0x7E) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasBody(HttpServletRequest request) {
        long length = request.getContentLengthLong();
        if (length > 0) {
            return true;
        }
        return length < 0 && request.getHeader("Transfer-Encoding") != null;
    }

    private static String routeTemplate(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern != null ? pattern.toString() : request.getRequestURI();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> pathVariables(HttpServletRequest request) {
        Object vars = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        return vars instanceof Map<?, ?> m ? (Map<String, String>) m : Map.of();
    }

    private static String multipartHash(HttpServletRequest request, String template, Map<String, String> vars)
            throws IOException {
        MultipartHttpServletRequest multipart = WebUtils.getNativeRequest(request, MultipartHttpServletRequest.class);
        Map<String, List<String>> fields = new LinkedHashMap<>();
        request.getParameterMap().forEach((name, values) -> fields.put(name, List.of(values)));
        Map<String, List<String>> files = new LinkedHashMap<>();
        if (multipart != null) {
            for (Map.Entry<String, List<MultipartFile>> e : multipart.getMultiFileMap().entrySet()) {
                List<String> digests = new ArrayList<>();
                for (MultipartFile file : e.getValue()) {
                    digests.add(sha256(file));
                }
                files.put(e.getKey(), digests);
            }
        }
        return IdempotencyRequestHasher.hashMultipart(template, vars, fields, files);
    }

    private static String sha256(MultipartFile file) throws IOException {
        try (InputStream in = new DigestInputStream(file.getInputStream(), MessageDigest.getInstance("SHA-256"))) {
            in.transferTo(java.io.OutputStream.nullOutputStream());
            return HexFormat.of().formatHex(((DigestInputStream) in).getMessageDigest().digest());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
