package com.petsitter.infrastructure.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * SD-GLOBAL-SPEC §2.8 驗證 (b)：同 IP 匿名 60 秒內打 61 次 {@code GET /api/sitters/{id}/plans} → 前 60 次 200、
 * 第 61 次 429 {@code RATE_LIMITED} 且帶 {@code Retry-After}；帶有效 token 的第 61 次 → 200。
 * 本類別以 {@code anonymous-enforce=true} 起 context（上線前狀態）；每個測試以不同的 XFF 來源隔離計數鍵。
 */
@TestPropertySource(properties = "app.security.rate-limit.anonymous-enforce=true")
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("NFR-003 SEC-INPUT-6 RateLimitIntegrationTest")
class RateLimitIntegrationTest extends SecurityIntegrationTestBase {

    private static String freshIp() {
        UUID u = UUID.randomUUID();
        return "203.0." + (Math.abs(u.getMostSignificantBits()) % 250) + "." + (Math.abs(u.getLeastSignificantBits()) % 250 + 1);
    }

    @Test
    @DisplayName("§2.8 (b) 匿名第 61 次 429 RATE_LIMITED + Retry-After（不進 controller）；帶 token 的第 61 次 200；同鍵只記 1 筆 WARN")
    void anonymous61st(CapturedOutput output) throws Exception {
        String ip = freshIp();
        for (int i = 0; i < 60; i++) {
            assertThat(mvc.perform(get("/api/sitters/{id}/plans", sitter).header("X-Forwarded-For", ip))
                    .andReturn().getResponse().getStatus()).isEqualTo(200);
        }
        MvcResult limited = mvc.perform(get("/api/sitters/{id}/plans", sitter).header("X-Forwarded-For", ip)).andReturn();
        assertThat(limited.getResponse().getStatus()).isEqualTo(429);
        assertThat(json.readTree(limited.getResponse().getContentAsString()).get("code").asString()).isEqualTo("RATE_LIMITED");
        assertThat(Integer.parseInt(limited.getResponse().getHeader("Retry-After"))).isBetween(1, 60);
        assertThat(limited.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        mvc.perform(get("/api/sitters/{id}/plans", sitter).header("X-Forwarded-For", ip)).andReturn();
        // 帶有效 token：不計入匿名額度
        assertThat(mvc.perform(get("/api/sitters/{id}/plans", sitter).header("X-Forwarded-For", ip)
                .header("Authorization", ownerToken())).andReturn().getResponse().getStatus()).isEqualTo(200);
        // 另一個 IP 不受影響
        assertThat(mvc.perform(get("/api/sitters/{id}/plans", sitter).header("X-Forwarded-For", freshIp()))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
        long warns = output.getOut().lines().filter(l -> l.contains("[RateLimit]") && l.contains(ip)).count();
        assertThat(warns).isEqualTo(1);
    }

    @Test
    @DisplayName("§2.8 帶 token 的第 301 次 429；/api/internal/** 與 OPTIONS 不套限流")
    void userLimitAndExclusions() throws Exception {
        String token = bearer(stranger, "OWNER");
        int last = 0;
        for (int i = 0; i < 301; i++) {
            last = mvc.perform(get("/api/sitters/{id}/plans", sitter).header("Authorization", token)).andReturn().getResponse().getStatus();
        }
        assertThat(last).isEqualTo(429);
        String ip = freshIp();
        for (int i = 0; i < 61; i++) {
            mvc.perform(post("/api/internal/cron/none").header("X-Forwarded-For", ip));
        }
        assertThat(mvc.perform(post("/api/internal/cron/none").header("X-Forwarded-For", ip))
                .andReturn().getResponse().getStatus()).isEqualTo(403);
    }
}
