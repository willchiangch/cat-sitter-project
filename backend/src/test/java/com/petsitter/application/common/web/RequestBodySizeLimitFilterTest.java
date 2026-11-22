package com.petsitter.application.common.web;

import com.petsitter.application.exception.RequestBodyTooLargeException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import jakarta.servlet.http.HttpServletRequest;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SD-GLOBAL-SPEC §2.8 第 1 列：chunked（無 Content-Length）body 以計數串流包裝，讀到第 1,048,577 bytes 中止。 */
@DisplayName("NFR-003 SEC-INPUT-1 RequestBodySizeLimitFilterTest")
class RequestBodySizeLimitFilterTest {

    private final RequestBodySizeLimitFilter filter = new RequestBodySizeLimitFilter(JsonMapper.builder().build());

    /** 模擬 chunked：Content-Length 未知。 */
    private static MockHttpServletRequest chunked(byte[] body) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/x") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }

            @Override
            public int getContentLength() {
                return -1;
            }
        };
        req.setContentType("application/json");
        req.setContent(body);
        return req;
    }

    @Test
    @DisplayName("chunked 超過 1 MB → 讀取途中拋 RequestBodyTooLargeException（maxBytes=1048576），已讀不超過上限 + 1")
    void chunkedOverLimit() throws Exception {
        AtomicReference<Long> readBeforeFailure = new AtomicReference<>(0L);
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) {
                InputStream in;
                try {
                    in = ((HttpServletRequest) request).getInputStream();
                    byte[] buf = new byte[8192];
                    long total = 0;
                    try {
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            total += n;
                        }
                    } finally {
                        readBeforeFailure.set(total);
                    }
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }
        };
        assertThatThrownBy(() -> filter.doFilter(chunked(new byte[2_000_000]), new MockHttpServletResponse(), chain))
                .hasCauseInstanceOf(RequestBodyTooLargeException.class);
        assertThat(readBeforeFailure.get()).isLessThanOrEqualTo(RequestBodySizeLimitFilter.MAX_BODY_BYTES);
    }

    @Test
    @DisplayName("chunked 剛好 1 MB → 完整讀出；multipart 不套用")
    void chunkedAtLimitAndMultipart() throws Exception {
        AtomicReference<Integer> size = new AtomicReference<>();
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) {
                try {
                    size.set(request.getInputStream().readAllBytes().length);
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }
        };
        filter.doFilter(chunked(new byte[(int) RequestBodySizeLimitFilter.MAX_BODY_BYTES]), new MockHttpServletResponse(), chain);
        assertThat(size.get()).isEqualTo((int) RequestBodySizeLimitFilter.MAX_BODY_BYTES);

        MockHttpServletRequest multipart = new MockHttpServletRequest("POST", "/api/x");
        multipart.setContentType("multipart/form-data; boundary=x");
        multipart.setContent(new byte[2_000_000]);
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(multipart, res, chain);
        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(size.get()).isEqualTo(2_000_000);
    }
}
