package com.petsitter.application.common.idempotency;

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

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.SequenceInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * 讓冪等攔截器能先讀 JSON body 算 hash、controller 仍能再讀一次（SD-GLOBAL-SPEC §2.4「讀 body」）。
 * <p>
 * 在 handler mapping 之前執行、看不到 {@link Idempotent}，以 {@code Content-Length} 判斷，<b>絕不讀超過上限的串流</b>：
 * <ol type="a">
 *   <li>帶 {@code Idempotency-Key}、Content-Type 為 JSON、{@code Content-Length} 存在且 ≤ 64 KB → 讀成 byte[] 並包成可重讀 wrapper</li>
 *   <li>{@code Content-Length} &gt; 64 KB → 不讀、不包裝，只設 request attribute {@value #BODY_TOO_LARGE_ATTRIBUTE}</li>
 *   <li>無 {@code Content-Length}（chunked）→ 最多讀 64 KB + 1 位元組；未超過照 (a)，超過則以「已讀前綴 + 剩餘原串流」
 *       的 wrapper 交回（非冪等 handler 仍拿到完整 body）並設同一 attribute</li>
 * </ol>
 * 未帶 header 或非 JSON（含 multipart，由 {@code MultipartFile} 重讀）的請求完全不經處理。
 * 排在 Spring Security filter chain 之後：401 的請求不讀 body。
 */
@Component
@Order(0)
public class IdempotencyBodyCachingFilter extends OncePerRequestFilter {

    public static final String HEADER = "Idempotency-Key";
    public static final String BODY_TOO_LARGE_ATTRIBUTE = "idempotency.bodyTooLarge";
    public static final int MAX_BODY_BYTES = 64 * 1024;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getHeader(HEADER) == null || !isJson(request.getContentType())) {
            chain.doFilter(request, response);
            return;
        }
        long contentLength = request.getContentLengthLong();
        if (contentLength > MAX_BODY_BYTES) {
            request.setAttribute(BODY_TOO_LARGE_ATTRIBUTE, Boolean.TRUE);
            chain.doFilter(request, response);
            return;
        }
        InputStream in = request.getInputStream();
        if (contentLength >= 0) {
            byte[] body = in.readNBytes((int) contentLength);
            chain.doFilter(new CachedBodyRequest(request, body), response);
            return;
        }
        byte[] prefix = in.readNBytes(MAX_BODY_BYTES + 1);
        if (prefix.length <= MAX_BODY_BYTES) {
            chain.doFilter(new CachedBodyRequest(request, prefix), response);
            return;
        }
        request.setAttribute(BODY_TOO_LARGE_ATTRIBUTE, Boolean.TRUE);
        chain.doFilter(new PrefixedBodyRequest(request, prefix, in), response);
    }

    static boolean isJson(String contentType) {
        if (contentType == null) {
            return false;
        }
        try {
            MediaType type = MediaType.parseMediaType(contentType);
            return MediaType.APPLICATION_JSON.isCompatibleWith(type)
                    || type.getSubtype().endsWith("+json");
        } catch (RuntimeException e) {
            return false;
        }
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

    /** 已完整讀入（≤ 64 KB）的 body，可重複讀。 */
    static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        byte[] body() {
            return body;
        }

        @Override
        public ServletInputStream getInputStream() {
            return new StreamAdapter(new ByteArrayInputStream(body), body.length == 0);
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body), charset(this)));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }

    /** chunked 且超過上限：已讀前綴 + 剩餘原串流，讓非冪等 handler 仍拿到完整 body。 */
    static final class PrefixedBodyRequest extends HttpServletRequestWrapper {

        private final ServletInputStream stream;

        PrefixedBodyRequest(HttpServletRequest request, byte[] prefix, InputStream rest) {
            super(request);
            this.stream = new StreamAdapter(new SequenceInputStream(new ByteArrayInputStream(prefix), rest), false);
        }

        @Override
        public ServletInputStream getInputStream() {
            return stream;
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(stream, charset(this)));
        }
    }

    private static Charset charset(HttpServletRequest request) {
        String encoding = request.getCharacterEncoding();
        try {
            return encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
        } catch (RuntimeException e) {
            return StandardCharsets.UTF_8;
        }
    }

    private static final class StreamAdapter extends ServletInputStream {

        private final InputStream delegate;
        private boolean finished;

        StreamAdapter(InputStream delegate, boolean finished) {
            this.delegate = delegate;
            this.finished = finished;
        }

        @Override
        public int read() throws IOException {
            int b = delegate.read();
            if (b < 0) {
                finished = true;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = delegate.read(b, off, len);
            if (n < 0) {
                finished = true;
            }
            return n;
        }

        @Override
        public boolean isFinished() {
            return finished;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            throw new UnsupportedOperationException("async read not supported");
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
