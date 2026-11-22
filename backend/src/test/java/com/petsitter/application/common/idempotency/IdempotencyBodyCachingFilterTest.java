package com.petsitter.application.common.idempotency;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** SD-GLOBAL §2.4「讀 body」(a)(b)(c)：絕不讀超過上限的串流。 */
@DisplayName("SD-GLOBAL §2.4 IdempotencyBodyCachingFilter")
class IdempotencyBodyCachingFilterTest {

    private static final int MAX = IdempotencyBodyCachingFilter.MAX_BODY_BYTES;
    private final IdempotencyBodyCachingFilter filter = new IdempotencyBodyCachingFilter();

    /** 模擬容器串流：記錄被讀了多少位元組；contentLength = -1 模擬 chunked。 */
    static final class CountingRequest extends HttpServletRequestWrapper {
        final AtomicInteger read = new AtomicInteger();
        private final ByteArrayInputStream data;
        private final long contentLength;

        CountingRequest(HttpServletRequest delegate, byte[] body, long contentLength) {
            super(delegate);
            this.data = new ByteArrayInputStream(body);
            this.contentLength = contentLength;
        }

        @Override
        public long getContentLengthLong() {
            return contentLength;
        }

        @Override
        public int getContentLength() {
            return (int) contentLength;
        }

        @Override
        public ServletInputStream getInputStream() {
            return new ServletInputStream() {
                @Override
                public int read() {
                    int b = data.read();
                    if (b >= 0) {
                        read.incrementAndGet();
                    }
                    return b;
                }

                @Override
                public boolean isFinished() {
                    return data.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener l) {
                }
            };
        }
    }

    private static MockHttpServletRequest base(boolean withKey, String contentType) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", "/api/x");
        if (withKey) {
            r.addHeader(IdempotencyBodyCachingFilter.HEADER, "k1");
        }
        r.setContentType(contentType);
        return r;
    }

    private static byte[] body(int size) {
        byte[] b = new byte[size];
        java.util.Arrays.fill(b, (byte) 'x');
        return b;
    }

    private ServletRequest run(HttpServletRequest request) throws Exception {
        AtomicReference<ServletRequest> passed = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> passed.set(req));
        return passed.get();
    }

    private static byte[] readAll(ServletRequest req) throws IOException {
        return req.getInputStream().readAllBytes();
    }

    @Test
    @DisplayName("(a) 帶 key、JSON、Content-Length ≤ 64 KB → 讀成 byte[]，可重讀兩次")
    void cachesSmallBody() throws Exception {
        byte[] data = "{\"a\":1}".getBytes();
        CountingRequest req = new CountingRequest(base(true, "application/json"), data, data.length);
        ServletRequest passed = run(req);
        assertThat(passed).isInstanceOf(IdempotencyBodyCachingFilter.CachedBodyRequest.class);
        assertThat(readAll(passed)).isEqualTo(data);
        assertThat(readAll(passed)).isEqualTo(data);
        assertThat(req.getAttribute(IdempotencyBodyCachingFilter.BODY_TOO_LARGE_ATTRIBUTE)).isNull();
    }

    @Test
    @DisplayName("(b) Content-Length > 64 KB → 一個位元組都不讀、不包裝，只設 idempotency.bodyTooLarge")
    void neverReadsOversizedStream() throws Exception {
        CountingRequest req = new CountingRequest(base(true, "application/json"), body(MAX + 10), MAX + 10);
        ServletRequest passed = run(req);
        assertThat(req.read.get()).isZero();
        assertThat(passed).isSameAs(req);
        assertThat(req.getAttribute("idempotency.bodyTooLarge")).isEqualTo(Boolean.TRUE);
    }

    @Test
    @DisplayName("(c) chunked 未超過 → 照 (a)")
    void chunkedSmall() throws Exception {
        CountingRequest req = new CountingRequest(base(true, "application/json"), body(100), -1);
        ServletRequest passed = run(req);
        assertThat(passed).isInstanceOf(IdempotencyBodyCachingFilter.CachedBodyRequest.class);
        assertThat(readAll(passed)).hasSize(100);
        assertThat(req.getAttribute(IdempotencyBodyCachingFilter.BODY_TOO_LARGE_ATTRIBUTE)).isNull();
    }

    @Test
    @DisplayName("(c) chunked 超過 → 只讀 64 KB + 1；交回「前綴 + 剩餘串流」讓下游拿到完整 body，並設 attribute")
    void chunkedOversized() throws Exception {
        int size = MAX * 2;
        CountingRequest req = new CountingRequest(base(true, "application/json"), body(size), -1);
        AtomicInteger readBeforeHandler = new AtomicInteger();
        AtomicReference<ServletRequest> passed = new AtomicReference<>();
        filter.doFilter(req, new MockHttpServletResponse(), (r, res) -> {
            readBeforeHandler.set(req.read.get());
            passed.set(r);
        });
        assertThat(readBeforeHandler.get()).isEqualTo(MAX + 1);
        assertThat(req.getAttribute(IdempotencyBodyCachingFilter.BODY_TOO_LARGE_ATTRIBUTE)).isEqualTo(Boolean.TRUE);
        assertThat(readAll(passed.get())).hasSize(size);
    }

    @Test
    @DisplayName("未帶 key 或非 JSON（含 multipart）→ 完全不經處理")
    void untouchedWithoutKeyOrNonJson() throws Exception {
        CountingRequest noKey = new CountingRequest(base(false, "application/json"), body(10), 10);
        assertThat(run(noKey)).isSameAs(noKey);
        assertThat(noKey.read.get()).isZero();

        CountingRequest multipart = new CountingRequest(base(true, "multipart/form-data; boundary=x"), body(MAX + 10), MAX + 10);
        assertThat(run(multipart)).isSameAs(multipart);
        assertThat(multipart.getAttribute(IdempotencyBodyCachingFilter.BODY_TOO_LARGE_ATTRIBUTE)).isNull();
        assertThat(multipart.read.get()).isZero();
    }

    @Test
    @DisplayName("JSON 判定：application/json、帶 charset、+json")
    void jsonDetection() {
        assertThat(IdempotencyBodyCachingFilter.isJson("application/json;charset=UTF-8")).isTrue();
        assertThat(IdempotencyBodyCachingFilter.isJson("application/merge-patch+json")).isTrue();
        assertThat(IdempotencyBodyCachingFilter.isJson("text/plain")).isFalse();
        assertThat(IdempotencyBodyCachingFilter.isJson(null)).isFalse();
        assertThat(IdempotencyBodyCachingFilter.isJson("garbage;;")).isFalse();
    }

    @Test
    @DisplayName("Idempotency-Key 格式：1–128 個可見 ASCII")
    void keyFormat() {
        assertThat(IdempotencyInterceptor.isValidKey("a")).isTrue();
        assertThat(IdempotencyInterceptor.isValidKey("x".repeat(128))).isTrue();
        assertThat(IdempotencyInterceptor.isValidKey("x".repeat(129))).isFalse();
        assertThat(IdempotencyInterceptor.isValidKey("")).isFalse();
        assertThat(IdempotencyInterceptor.isValidKey("a b")).isFalse();
        assertThat(IdempotencyInterceptor.isValidKey("é")).isFalse();
    }
}
