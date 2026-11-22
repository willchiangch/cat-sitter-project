package com.petsitter.application.common.web;

import com.petsitter.application.exception.ApiErrors;
import com.petsitter.application.exception.CommonErrorCode;
import com.petsitter.application.exception.ErrorResponse;
import com.petsitter.application.exception.RequestBodyTooLargeException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 非冪等 body 上限（SD-GLOBAL-SPEC §2.8 第 1 列、NFR-003 SEC-INPUT-1、CQ-25）：非 multipart 請求 body 上限
 * {@value #MAX_BODY_BYTES} bytes。排在 {@code IdempotencyBodyCachingFilter}（{@code @Order(0)}）之前。
 * <ul>
 *   <li>{@code Content-Length} &gt; 上限 → 不讀串流，直接 400 {@code VALIDATION_FAILED}（{@code field=body}、{@code maxBytes}）；</li>
 *   <li>無 {@code Content-Length}（chunked）→ 以計數串流包裝，讀到第 {@value #MAX_BODY_BYTES}+1 bytes 時拋
 *       {@link RequestBodyTooLargeException} → 400 同上。不先整包讀入再判斷。</li>
 * </ul>
 * multipart 適用全域 multipart 上限（{@code spring.servlet.multipart.*}），本 filter 不處理。
 */
@Component
@Order(-1)
public class RequestBodySizeLimitFilter extends OncePerRequestFilter {

    public static final long MAX_BODY_BYTES = 1_048_576L;

    private final JsonMapper jsonMapper;

    public RequestBodySizeLimitFilter(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (isMultipart(request.getContentType())) {
            chain.doFilter(request, response);
            return;
        }
        long contentLength = request.getContentLengthLong();
        if (contentLength > MAX_BODY_BYTES) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("field", "body");
            payload.put("maxBytes", MAX_BODY_BYTES);
            CommonErrorCode code = CommonErrorCode.VALIDATION_FAILED;
            ErrorResponse body = ApiErrors.build(request, code.name(), code.defaultMessage(), payload);
            response.setStatus(code.status().value());
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(jsonMapper.writeValueAsString(body));
            return;
        }
        if (contentLength < 0) {
            chain.doFilter(new LimitedBodyRequest(request), response);
            return;
        }
        chain.doFilter(request, response);
    }

    static boolean isMultipart(String contentType) {
        if (contentType == null) {
            return false;
        }
        try {
            return "multipart".equalsIgnoreCase(MediaType.parseMediaType(contentType).getType());
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** chunked body：讀超過上限時中止。 */
    static final class LimitedBodyRequest extends HttpServletRequestWrapper {

        private ServletInputStream stream;

        LimitedBodyRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                stream = new CountingInputStream(super.getInputStream());
            }
            return stream;
        }

        @Override
        public java.io.BufferedReader getReader() throws IOException {
            String enc = getCharacterEncoding();
            return new java.io.BufferedReader(new java.io.InputStreamReader(getInputStream(),
                    enc != null ? java.nio.charset.Charset.forName(enc) : java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    static final class CountingInputStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private long count;

        CountingInputStream(ServletInputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public int read() throws IOException {
            int b = delegate.read();
            if (b >= 0) {
                add(1);
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            // 一次最多讀到「上限 + 1」為止，避免單次 read 吞下大量資料才判斷
            long allowed = MAX_BODY_BYTES + 1 - count;
            int n = delegate.read(b, off, (int) Math.min(len, allowed));
            if (n > 0) {
                add(n);
            }
            return n;
        }

        private void add(long n) throws RequestBodyTooLargeException {
            count += n;
            if (count > MAX_BODY_BYTES) {
                throw new RequestBodyTooLargeException(MAX_BODY_BYTES);
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }
    }
}
