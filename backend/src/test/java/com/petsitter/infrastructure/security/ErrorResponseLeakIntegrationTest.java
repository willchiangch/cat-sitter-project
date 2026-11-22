package com.petsitter.infrastructure.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MvcResult;
import testsupport.security.LeakTestController;
import tools.jackson.databind.JsonNode;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * SD-GLOBAL-SPEC §2.6「message 不得帶例外原文」、NFR-003 SEC-LOG-2：(a)–(f) 回應 body 只有 5 欄，
 * 皆不含原例外訊息、約束名、Jackson 解析訊息。
 */
@DisplayName("NFR-003 SEC-LOG-2 ErrorResponseLeakIntegrationTest")
class ErrorResponseLeakIntegrationTest extends SecurityIntegrationTestBase {

    private String ownerTokenValue() {
        return bearer(owner, "OWNER");
    }

    private MvcResult trigger(String scenario) throws Exception {
        return mvc.perform(post("/api/test-leak/{s}", scenario).header("Authorization", ownerTokenValue())).andReturn();
    }

    private void assertNoLeak(MvcResult r, String... forbidden) throws Exception {
        String body = r.getResponse().getContentAsString();
        JsonNode n = json.readTree(body);
        for (Map.Entry<String, JsonNode> e : n.properties()) {
            assertThat(e.getKey()).isIn("code", "message", "payload", "traceId", "timestamp");
        }
        assertThat(body).doesNotContain(LeakTestController.SECRET_DETAIL)
                .doesNotContain("secret_marker").doesNotContain(LeakTestController.CONSTRAINT)
                .doesNotContain("Exception").doesNotContain("SELECT").doesNotContain("at com.");
        for (String f : forbidden) {
            assertThat(body).doesNotContain(f);
        }
    }

    @Test
    @DisplayName("SEC-LOG-2 (a) 訊息含 SELECT 的未處理 RuntimeException → 500 INTERNAL_ERROR 固定文案")
    void unhandledRuntime() throws Exception {
        MvcResult r = trigger("runtime");
        assertThat(r.getResponse().getStatus()).isEqualTo(500);
        assertThat(json.readTree(r.getResponse().getContentAsString()).get("code").asString()).isEqualTo("INTERNAL_ERROR");
        assertNoLeak(r);
    }

    @Test
    @DisplayName("SEC-LOG-2 (b) SQLSTATE 23514（未重建模組）→ 400 DATA_ERROR 固定文案，不含約束名 / 表名")
    void checkViolation() throws Exception {
        MvcResult r = trigger("check");
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(json.readTree(r.getResponse().getContentAsString()).get("code").asString()).isEqualTo("DATA_ERROR");
        assertNoLeak(r, "users", "relation");
    }

    @Test
    @DisplayName("SEC-LOG-2 (c) 不合法 JSON → 400 VALIDATION_FAILED，不含 Jackson 解析訊息")
    void invalidJson() throws Exception {
        MvcResult r = mvc.perform(post("/api/test-leak/json").header("Authorization", ownerTokenValue())
                .contentType(MediaType.APPLICATION_JSON).content("{\"a\": tru")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(json.readTree(r.getResponse().getContentAsString()).get("code").asString()).isEqualTo("VALIDATION_FAILED");
        assertNoLeak(r, "Unrecognized", "Unexpected", "line:", "column:", "token");
    }

    @Test
    @DisplayName("SEC-LOG-2 (d) AuthenticationException → 401 固定文案（不再拼接 getMessage）")
    void authenticationException() throws Exception {
        MvcResult r = trigger("authentication");
        assertThat(r.getResponse().getStatus()).isEqualTo(401);
        assertThat(json.readTree(r.getResponse().getContentAsString()).get("message").asString()).isEqualTo("認證失敗");
        assertNoLeak(r);
    }

    @Test
    @DisplayName("SEC-LOG-2 (e) IllegalArgumentException → 400 VALIDATION_FAILED；IllegalStateException → 500 INTERNAL_ERROR")
    void illegalArgumentAndState() throws Exception {
        MvcResult r = trigger("illegal-argument");
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(json.readTree(r.getResponse().getContentAsString()).get("code").asString()).isEqualTo("VALIDATION_FAILED");
        assertNoLeak(r);
        MvcResult s = trigger("illegal-state");
        assertThat(s.getResponse().getStatus()).isEqualTo(500);
        assertThat(json.readTree(s.getResponse().getContentAsString()).get("code").asString()).isEqualTo("INTERNAL_ERROR");
        assertNoLeak(s);
    }

    @Test
    @DisplayName("SEC-LOG-2 (f) 73 bytes 密碼登入 → 錯誤回應不含 BCrypt 例外訊息")
    void password73Bytes() throws Exception {
        String email = "bcrypt-" + UUID.randomUUID() + "@t.test";
        jdbc.update("INSERT INTO users (id, email, password_hash, full_name, role) VALUES (?, ?, ?, 'b', 'OWNER')",
                UUID.randomUUID(), email, new BCryptPasswordEncoder().encode("Correct-Pass-1"));
        String pw = "a".repeat(73);
        MvcResult r = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email, "password", pw)))).andReturn();
        assertThat(r.getResponse().getStatus()).isBetween(400, 499);
        assertNoLeak(r, "72 bytes", "bytes", "larger", pw);
    }

    @Test
    @DisplayName("SD-GLOBAL §2.6 / SEC-LOG-5 traceId：X-Cloud-Trace-Context 含 CRLF → 回應 traceId 不等於輸入且符合 ^[0-9a-f]{32}$；合法值沿用")
    void traceIdValidated() throws Exception {
        MvcResult bad = mvc.perform(post("/api/test-leak/runtime").header("Authorization", ownerTokenValue())
                .header("X-Cloud-Trace-Context", "abc\r\nFAKE/1;o=1")).andReturn();
        String t1 = json.readTree(bad.getResponse().getContentAsString()).get("traceId").asString();
        assertThat(t1).matches("^[0-9a-f]{32}$").doesNotContain("abc").doesNotContain("FAKE");
        MvcResult upper = mvc.perform(post("/api/test-leak/runtime").header("Authorization", ownerTokenValue())
                .header("X-Cloud-Trace-Context", "ABCDEF0123456789ABCDEF0123456789/1;o=1")).andReturn();
        assertThat(json.readTree(upper.getResponse().getContentAsString()).get("traceId").asString())
                .matches("^[0-9a-f]{32}$").isNotEqualTo("ABCDEF0123456789ABCDEF0123456789");
        String good = "0123456789abcdef0123456789abcdef";
        MvcResult ok = mvc.perform(post("/api/test-leak/runtime").header("Authorization", ownerTokenValue())
                .header("X-Cloud-Trace-Context", good + "/123;o=1")).andReturn();
        assertThat(json.readTree(ok.getResponse().getContentAsString()).get("traceId").asString()).isEqualTo(good);
    }
}
