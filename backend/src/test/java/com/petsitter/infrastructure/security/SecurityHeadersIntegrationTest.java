package com.petsitter.infrastructure.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * SD-GLOBAL-SPEC §3.5、NFR-003 SEC-WEB-3：200、401、403、404、500 五種回應皆帶 6 個 API 資安標頭；
 * HSTS 不依賴 {@code request.isSecure()}（MockMvc 請求為 http）。
 */
@DisplayName("NFR-003 SEC-WEB-3 SecurityHeadersIntegrationTest")
class SecurityHeadersIntegrationTest extends SecurityIntegrationTestBase {

    private void assertHeaders(MockHttpServletRequestBuilder req, int expectedStatus) throws Exception {
        MvcResult r = mvc.perform(req).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(expectedStatus);
        var res = r.getResponse();
        assertThat(res.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(res.getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(res.getHeaders("Cache-Control")).containsExactly("no-store");
        assertThat(res.getHeader("Content-Security-Policy")).isEqualTo("default-src 'none'; frame-ancestors 'none'");
        assertThat(res.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(res.getHeader("Strict-Transport-Security")).isEqualTo("max-age=31536000; includeSubDomains");
    }

    @Test
    @DisplayName("§3.5 200 / 401 / 403（denied handler 與方法層）/ 404 / 500 皆帶 6 個標頭（http 請求也有 HSTS）")
    void allStatuses() throws Exception {
        assertHeaders(get("/api/sitters/{id}/plans", sitter), 200);
        assertHeaders(get("/api/orders/{id}", UUID.randomUUID()), 401);
        assertHeaders(get("/api/internal/cron/anything"), 403);
        assertHeaders(get("/api/orders/sitter/ledger?month=2026-11").header("Authorization", ownerToken()), 403);
        assertHeaders(get("/api/no-such-route").header("Authorization", ownerToken()), 404);
        assertHeaders(post("/api/test-leak/runtime").header("Authorization", ownerToken()), 500);
    }
}
