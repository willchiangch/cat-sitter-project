package com.petsitter.infrastructure.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * SD-GLOBAL-SPEC §3.7、NFR-003 SEC-INT-1：{@code /api/internal/**} 只認 {@code X-Internal-Secret}；
 * 缺漏、不符、只帶一般使用者 token、只帶 ADMIN token → 403 {@code FORBIDDEN}，不處理任何資料；log 不含標頭值。
 */
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("NFR-003 SEC-INT-1 InternalCronSecurityTest")
class InternalCronSecurityTest extends SecurityIntegrationTestBase {

    private static final String URL = "/api/internal/cron/notifications/cleanup";

    @Value("${app.security.internal-secret}")
    private String secret;

    private MvcResult call(UnaryOperator<MockHttpServletRequestBuilder> f) throws Exception {
        return mvc.perform(f.apply(post(URL))).andReturn();
    }

    @Test
    @DisplayName("SEC-INT-1.1 四種拒絕 → 403 FORBIDDEN；SEC-INT-1.3 log 只記路徑，不含標頭值")
    void rejections(CapturedOutput output) throws Exception {
        String wrong = "wrong-secret-value-7c1e9d";
        String longer = secret + "-but-longer-and-different";
        for (MvcResult r : new MvcResult[]{
                call(b -> b),
                call(b -> b.header(InternalSecretFilter.HEADER, wrong)),
                call(b -> b.header(InternalSecretFilter.HEADER, longer)),
                call(b -> b.header("Authorization", ownerToken())),
                call(b -> b.header("Authorization", bearer(admin, "ADMIN")))}) {
            assertThat(r.getResponse().getStatus()).isEqualTo(403);
            assertThat(r.getResponse().getContentAsString()).contains("\"FORBIDDEN\"");
        }
        assertThat(output.getOut()).contains("[InternalSecretFilter] rejected internal request")
                .doesNotContain(wrong).doesNotContain(longer);
        assertThat(call(b -> b.header(InternalSecretFilter.HEADER, secret)).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("§3.7 比對方式：雙邊 SHA-256 後 MessageDigest.isEqual（摘要長度固定 32 bytes）")
    void digestComparison() {
        assertThat(InternalSecretFilter.sha256("a")).hasSize(32);
        assertThat(InternalSecretFilter.sha256("a".repeat(1000))).hasSize(32);
    }
}
