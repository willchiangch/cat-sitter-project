package com.petsitter.infrastructure.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import tools.jackson.databind.JsonNode;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * SD-GLOBAL-SPEC §2.9（NFR-003 SEC-LOG-1、SEC-LOG-5）：log 擷取不含密碼、token、secret、收款帳號明文；
 * CR / LF 以 logback {@code %replace} 跳脫，使用者輸入無法偽造新行。IDENTITY / SUPPLY 的案例（改密碼、重設、OTP、
 * 修改收款帳號）隨各 SD 重建補上。
 */
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("NFR-003 SEC-LOG-1 / SEC-LOG-5 LogRedactionIntegrationTest")
class LogRedactionIntegrationTest extends SecurityIntegrationTestBase {

    private static final Logger TEST_LOG = LoggerFactory.getLogger(LogRedactionIntegrationTest.class);

    @Test
    @DisplayName("§2.9 CR / LF 注入：訊息中的 CRLF 以字面 \\r\\n 輸出，不出現偽造的新行")
    void crlfEscaped(CapturedOutput output) {
        TEST_LOG.warn("login failed for {}", "victim@x.com\r\n2026-11-22T00:00:00.000Z  INFO 1 --- FORGED-LINE-MARKER");
        assertThat(output.getOut()).contains("victim@x.com\\r\\n2026-11-22T00:00:00.000Z");
        assertThat(output.getOut().lines()).noneMatch(l -> l.startsWith("2026-11-22T00:00:00.000Z  INFO 1 --- FORGED-LINE-MARKER"));
    }

    @Test
    @DisplayName("§2.9 含 CRLF 的 email 登入失敗：擷取的 log 不出現偽造的新行")
    void crlfEmailLogin(CapturedOutput output) throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", "a@b.com\r\nFORGED-LOGIN-LINE", "password", "x"))));
        assertThat(output.getOut().lines()).noneMatch(l -> l.startsWith("FORGED-LOGIN-LINE"));
    }

    @Test
    @DisplayName("§2.9 登入成功 / 失敗、換發、內部端點錯誤 secret、建立 payment 快照：log 不含密碼、token、secret、帳號明文、X-Goog-Signature=")
    void sensitiveValuesNotLogged(CapturedOutput output) throws Exception {
        String password = "Pw-" + UUID.randomUUID();
        String email = "redact-" + UUID.randomUUID() + "@t.test";
        jdbc.update("INSERT INTO users (id, email, password_hash, full_name, role) VALUES (?, ?, ?, 'r', 'OWNER')",
                UUID.randomUUID(), email, new BCryptPasswordEncoder().encode(password));
        // 登入失敗
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email, "password", password + "-wrong"))));
        // 登入成功
        JsonNode auth = json.readTree(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email, "password", password)))).andReturn().getResponse().getContentAsString());
        String access = auth.get("accessToken").asString();
        String refresh = auth.get("refreshToken").asString();
        // 換發
        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("refreshToken", refresh))));
        // 內部端點錯誤 secret
        String wrongSecret = "internal-wrong-" + UUID.randomUUID();
        mvc.perform(post("/api/internal/cron/notifications/cleanup").header(InternalSecretFilter.HEADER, wrongSecret));
        // 建立 payment 快照（E6；收款帳號 812 / 1234567890123 / 王小明，見 OrderIntegrationTestBase.payout）
        UUID orderId = book(today().plusDays(5));
        quote(orderId);

        String out = output.getOut();
        assertThat(out).doesNotContain(password).doesNotContain(access).doesNotContain(refresh)
                .doesNotContain(wrongSecret).doesNotContain("1234567890123").doesNotContain("王小明")
                .doesNotContain("X-Goog-Signature=").doesNotContain(email);
    }
}
