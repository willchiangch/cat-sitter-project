package com.petsitter.fulfill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static testsupport.fulfill.MediaFiles.jpeg;

/** 真 JWT + 真 SecurityConfig filter chain。 */
@DisplayName("SD-FULFILL VisitCareLogAuthorizationIntegrationTest（F13–F19）")
class VisitCareLogAuthorizationIntegrationTest extends VisitCareLogTestBase {

    @Test
    @DisplayName("PRD-022 AC-3.3 401 / 他人保母寫入 403 / OWNER 與 ADMIN 寫入 ROLE_MISMATCH / 跨訂單路徑 404；F13 非當事人讀取不論日誌是否送出一律 404（SD-FULFILL §6.3 F13）；保母以 OWNER token 讀自己草稿 ROLE_MISMATCH；日誌媒體不變、無新物件")
    void authorization() throws Exception {
        UUID orderId = runningOrder("PRO", 1);
        UUID other = runningOrder("PRO", 1);
        UUID v = visits(orderId).get(0);
        byte[] img = jpeg(1000);
        UUID u = applyAndPut(orderId, v, img, img, "IMAGE", "image/jpeg", null, null, key());
        completeUpload(orderId, v, u, sitterToken()).andExpect(status().isCreated());
        UUID mediaId = jdbc.queryForObject("SELECT id FROM service_report_media WHERE upload_id = ?", UUID.class, u);
        done(v, OffsetDateTime.now(clock).minusMinutes(1));
        int objects = storage.objectCount();
        readLog(orderId, v, null).andExpect(status().isUnauthorized());
        draft(orderId, v, null, "x").andExpect(status().isUnauthorized());
        String s2 = strangerSitterToken();
        readLog(orderId, v, s2).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        draft(orderId, v, s2, "x").andExpect(status().isForbidden());
        requestUpload(orderId, v, s2, key(), file(jpeg(10), "IMAGE", "image/jpeg", null), null).andExpect(status().isForbidden());
        target(orderId, v, u, s2).andExpect(status().isForbidden());
        completeUpload(orderId, v, u, s2).andExpect(status().isForbidden());
        deleteAs(LOG + "/media/{m}", s2, orderId, v, mediaId).andExpect(status().isForbidden());
        submit(orderId, v, s2, key(), "x").andExpect(status().isForbidden());
        for (String t : List.of(ownerToken(), adminToken())) {
            draft(orderId, v, t, "x").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ROLE_MISMATCH"));
            requestUpload(orderId, v, t, key(), file(jpeg(10), "IMAGE", "image/jpeg", null), null).andExpect(jsonPath("$.code").value("ROLE_MISMATCH"));
            deleteAs(LOG + "/media/{m}", t, orderId, v, mediaId).andExpect(jsonPath("$.code").value("ROLE_MISMATCH"));
            submit(orderId, v, t, key(), "x").andExpect(jsonPath("$.code").value("ROLE_MISMATCH"));
        }
        draft(orderId, v, sitterToken(), "草稿").andExpect(status().isOk());
        readLog(orderId, v, bearer(sitter, "OWNER")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ROLE_MISMATCH"));
        String draftNotFound = readLog(orderId, v, strangerOwnerToken()).andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        readLog(orderId, UUID.randomUUID(), sitterToken()).andExpect(status().isNotFound());
        readLog(other, v, sitterToken()).andExpect(status().isNotFound());
        draft(other, v, sitterToken(), "x").andExpect(status().isNotFound());
        deleteAs(LOG + "/media/{m}", sitterToken(), other, v, mediaId).andExpect(status().isNotFound());
        submit(orderId, v, sitterToken(), key(), "ok").andExpect(status().isOk());
        // 已送出與未送出對非當事人回應完全相同（無 403 / 404 存在性預言）
        String submittedNotFound = readLog(orderId, v, strangerOwnerToken()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND")).andReturn().getResponse().getContentAsString();
        for (String field : List.of("code", "message", "payload")) {
            assertThat(json.readTree(submittedNotFound).get(field)).as(field).isEqualTo(json.readTree(draftNotFound).get(field));
        }
        readLog(orderId, v, s2).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        assertThat(mediaCount(v)).isEqualTo(1);
        assertThat(storage.objectCount()).isEqualTo(objects);
    }
}
