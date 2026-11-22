package com.petsitter.infrastructure.security;

import com.petsitter.application.exception.ApiErrors;
import com.petsitter.application.exception.CommonErrorCode;
import com.petsitter.application.exception.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;

/**
 * 內部端點（排程、test-data）的 {@code X-Internal-Secret} 驗證（SD-ORDER §6.3 C1–C10、§8.3 D3）：
 * 缺漏或不符 → 403 {@code FORBIDDEN}（§6.1 格式），不處理任何資料；不記錄標頭值，失敗只記路徑、來源 IP、traceId。
 * <p>
 * SD-GLOBAL-SPEC §3.7（NFR-003 SEC-INT-1、CQ-34）：啟動時先算設定值的 SHA-256；每次請求對收到的標頭值也做 SHA-256，
 * 再以 {@link MessageDigest#isEqual} 比較兩個 32 bytes 摘要（長度固定，避免長度不同時提早返回造成時間差）。
 */
@Slf4j
@Component
public class InternalSecretFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Internal-Secret";

    private final byte[] secretDigest;
    private final JsonMapper jsonMapper;
    private final ClientIpResolver ipResolver;

    public InternalSecretFilter(@Value("${app.security.internal-secret}") String internalSecret, JsonMapper jsonMapper,
                                ClientIpResolver ipResolver) {
        this.secretDigest = sha256(internalSecret);
        this.jsonMapper = jsonMapper;
        this.ipResolver = ipResolver;
    }

    static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (path.startsWith("/api/internal/")) {
            String provided = request.getHeader(HEADER);
            if (provided == null || !MessageDigest.isEqual(secretDigest, sha256(provided))) {
                log.warn("[InternalSecretFilter] rejected internal request traceId={} path={} ip={}",
                        ApiErrors.traceIdOf(request), path, ipResolver.resolve(request));
                ErrorResponse body = ApiErrors.build(request, CommonErrorCode.FORBIDDEN.name(), CommonErrorCode.FORBIDDEN.defaultMessage());
                response.setStatus(CommonErrorCode.FORBIDDEN.status().value());
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write(jsonMapper.writeValueAsString(body));
                return;
            }
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    "INTERNAL_CRON", null, Collections.singletonList(new SimpleGrantedAuthority("ROLE_INTERNAL"))));
        }
        filterChain.doFilter(request, response);
    }
}
