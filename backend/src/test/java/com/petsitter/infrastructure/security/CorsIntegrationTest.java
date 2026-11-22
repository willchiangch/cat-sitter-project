package com.petsitter.infrastructure.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;

/**
 * SD-GLOBAL-SPEC §3.5 CORS（NFR-003 SEC-WEB-4、G-8）：白名單逐一列出；非白名單來源不回 {@code Access-Control-Allow-Origin}；
 * 白名單來源回原值、{@code allowCredentials}、{@code exposedHeaders=Retry-After}、不再允許 {@code x-auth-token}。
 * 測試環境白名單 = {@code application.yml} 預設 {@code http://localhost:5173}。
 */
@DisplayName("NFR-003 SEC-WEB-4 CorsIntegrationTest")
class CorsIntegrationTest extends SecurityIntegrationTestBase {

    private static final String ALLOWED = "http://localhost:5173";

    @Test
    @DisplayName("§3.5 Origin: https://evil.example 的預檢 → 回應不含 Access-Control-Allow-Origin")
    void evilOriginRejected() throws Exception {
        MvcResult r = mvc.perform(options("/api/orders").header("Origin", "https://evil.example")
                .header("Access-Control-Request-Method", "POST")).andReturn();
        assertThat(r.getResponse().getHeader("Access-Control-Allow-Origin")).isNull();
    }

    @Test
    @DisplayName("§3.5 白名單來源預檢 → 回該來源原值、credentials true、maxAge 3600；x-auth-token 不在允許標頭")
    void allowedOriginPreflight() throws Exception {
        MvcResult r = mvc.perform(options("/api/orders").header("Origin", ALLOWED)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "authorization,content-type,idempotency-key")).andReturn();
        assertThat(r.getResponse().getHeader("Access-Control-Allow-Origin")).isEqualTo(ALLOWED);
        assertThat(r.getResponse().getHeader("Access-Control-Allow-Credentials")).isEqualTo("true");
        assertThat(r.getResponse().getHeader("Access-Control-Max-Age")).isEqualTo("3600");
        MvcResult bad = mvc.perform(options("/api/orders").header("Origin", ALLOWED)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "x-auth-token")).andReturn();
        assertThat(bad.getResponse().getHeader("Access-Control-Allow-Origin")).isNull();
    }

    @Test
    @DisplayName("§3.5 實際請求：白名單來源 → exposedHeaders 只有 Retry-After")
    void exposedHeaders() throws Exception {
        MvcResult r = mvc.perform(get("/api/sitters/{id}/plans", sitter).header("Origin", ALLOWED)).andReturn();
        assertThat(r.getResponse().getHeader("Access-Control-Allow-Origin")).isEqualTo(ALLOWED);
        assertThat(r.getResponse().getHeader("Access-Control-Expose-Headers")).isEqualTo("Retry-After");
    }

    @Test
    @DisplayName("§3.5 prod 缺值 → 啟動失敗；含 * → 啟動失敗；非 prod 可為空")
    void originValidation() {
        assertThatThrownBy(() -> SecurityConfig.validatedOrigins(List.of(), true)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> SecurityConfig.validatedOrigins(List.of(" "), true)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> SecurityConfig.validatedOrigins(List.of("*"), false)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> SecurityConfig.validatedOrigins(List.of("https://*.web.app"), true)).isInstanceOf(IllegalStateException.class);
        assertThat(SecurityConfig.validatedOrigins(List.of("https://a.web.app", "https://a.firebaseapp.com"), true))
                .containsExactly("https://a.web.app", "https://a.firebaseapp.com");
        assertThat(SecurityConfig.validatedOrigins(List.of(), false)).isEmpty();
    }
}
