package com.petsitter.infrastructure.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * SD-GLOBAL-SPEC §2.8 請求解析與濫用防護（NFR-003 SEC-INPUT-1、3、5、6）：非冪等 body 1 MB、金額 BigDecimal 範圍、
 * 分頁上限 100、匿名限流預設只記不擋。
 */
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("NFR-003 SEC-INPUT RequestParsingIntegrationTest")
class RequestParsingIntegrationTest extends SecurityIntegrationTestBase {

    @Test
    @DisplayName("SEC-INPUT-1 非 multipart body Content-Length > 1,048,576 → 400 VALIDATION_FAILED（field=body、maxBytes），不進 controller")
    void bodyTooLarge() throws Exception {
        byte[] big = ("{\"a\":\"" + "x".repeat(1_048_576) + "\"}").getBytes(StandardCharsets.UTF_8);
        MvcResult r = mvc.perform(post("/api/test-leak/json").header("Authorization", ownerToken())
                .contentType(MediaType.APPLICATION_JSON).content(big)).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        JsonNode n = json.readTree(r.getResponse().getContentAsString());
        assertThat(n.get("code").asString()).isEqualTo("VALIDATION_FAILED");
        assertThat(n.get("payload").get("field").asString()).isEqualTo("body");
        assertThat(n.get("payload").get("maxBytes").asLong()).isEqualTo(1_048_576L);
        // 剛好在上限內 → 正常
        byte[] ok = ("{\"a\":\"" + "x".repeat(1_048_576 - 10) + "\"}").getBytes(StandardCharsets.UTF_8);
        assertThat(ok.length).isLessThanOrEqualTo(1_048_576);
        assertThat(mvc.perform(post("/api/test-leak/json").header("Authorization", ownerToken())
                .contentType(MediaType.APPLICATION_JSON).content(ok)).andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("SEC-INPUT-3 金額 1e999999999 → 400 VALIDATION_FAILED（field=expectedAmount）且回應 < 1 秒（E1 冪等 hash 不展開）")
    void hugeAmount() throws Exception {
        String body = "{\"sitterId\":\"" + sitter + "\",\"groups\":[{\"planId\":\"" + planA + "\",\"dates\":[\""
                + today().plusDays(5) + "\"],\"dailyCount\":1,\"petIds\":[\"" + petA + "\"]}],\"expectedAmount\":1e999999999}";
        Instant start = Instant.now();
        MvcResult r = mvc.perform(postJson("/api/orders", ownerToken(), key(), body)).andReturn();
        assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofSeconds(1));
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        JsonNode n = json.readTree(r.getResponse().getContentAsString());
        assertThat(n.get("code").asString()).isEqualTo("VALIDATION_FAILED");
        assertThat(n.get("payload").get("field").asString()).isEqualTo("expectedAmount");
        // 負指數同樣在任何運算前拒絕
        String neg = body.replace("1e999999999", "1e-999999999");
        Instant s2 = Instant.now();
        MvcResult r2 = mvc.perform(postJson("/api/orders", ownerToken(), key(), neg)).andReturn();
        assertThat(Duration.between(s2, Instant.now())).isLessThan(Duration.ofSeconds(1));
        assertThat(r2.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("SEC-INPUT-5 分頁 size 預設 20、上限 100：size=101 → 400 VALIDATION_FAILED（field=size、max=100）；size=100 → 200")
    void pageSize() throws Exception {
        MvcResult r = mvc.perform(get("/api/notifications?size=101").header("Authorization", ownerToken())).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        JsonNode n = json.readTree(r.getResponse().getContentAsString());
        assertThat(n.get("payload").get("field").asString()).isEqualTo("size");
        assertThat(n.get("payload").get("max").asInt()).isEqualTo(100);
        assertThat(mvc.perform(get("/api/notifications?size=100").header("Authorization", ownerToken()))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(mvc.perform(get("/api/notifications").header("Authorization", ownerToken()))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("SEC-INPUT-6 anonymous-enforce=false（預設）：匿名第 61 次仍 200，只記 WARN")
    void anonymousNotEnforcedByDefault(CapturedOutput output) throws Exception {
        String ip = "198.18.0." + (UUID.randomUUID().hashCode() & 0x7f);
        int last = 0;
        for (int i = 0; i < 61; i++) {
            last = mvc.perform(get("/api/sitters/{id}/plans", sitter).header("X-Forwarded-For", ip)).andReturn().getResponse().getStatus();
        }
        assertThat(last).isEqualTo(200);
        assertThat(output.getOut()).contains("[RateLimit] limit exceeded").contains("enforced=false");
    }
}
