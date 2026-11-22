package com.petsitter.fulfill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("SD-FULFILL VisitCareLogMediaDeleteIntegrationTest（F19）")
class VisitCareLogMediaDeleteIntegrationTest extends VisitCareLogTestBase {

    private UUID mediaOf(UUID uploadId) {
        return jdbc.queryForObject("SELECT id FROM service_report_media WHERE upload_id = ?", UUID.class, uploadId);
    }

    @Test
    @DisplayName("PRD-022 AC-2.6 刪除釋放額度（5/5 → 4/5 → 再上傳 5/5）、MEDIA_DELETED、槽位 DISCARDED、GCS 物件刪除；重送 404；以 V2 路徑刪 V1 媒體 404")
    void deleteReleasesQuota() throws Exception {
        UUID orderId = runningOrder("BASIC", 2);
        List<UUID> vs = visits(orderId);
        UUID v = vs.get(0);
        UUID first = null;
        for (int i = 0; i < 5; i++) {
            byte[] img = testsupport.fulfill.MediaFiles.jpeg(1000 + i);
            UUID u = applyAndPut(orderId, v, img, img, "IMAGE", "image/jpeg", null, null, key());
            completeUpload(orderId, v, u, sitterToken()).andExpect(status().isCreated());
            if (first == null) {
                first = u;
            }
        }
        UUID m1 = mediaOf(first);
        String key = objectKey(first);
        deleteAs(LOG + "/media/{m}", sitterToken(), orderId, vs.get(1), m1).andExpect(status().isNotFound());
        deleteAs(LOG + "/media/{m}", sitterToken(), orderId, v, m1).andExpect(status().isOk())
                .andExpect(jsonPath("$.mediaId").value(m1.toString())).andExpect(jsonPath("$.quota.imageUsed").value(4));
        assertThat(slotStatus(first)).isEqualTo("DISCARDED");
        assertThat(storage.exists(key)).isFalse();
        assertThat(jdbc.queryForObject("SELECT object_deleted_at FROM care_log_media_uploads WHERE id = ?", java.time.OffsetDateTime.class, first)).isNull();
        assertThat(logCount(orderId, "MEDIA_DELETED")).isEqualTo(1);
        deleteAs(LOG + "/media/{m}", sitterToken(), orderId, v, m1).andExpect(status().isNotFound());
        uploadJpeg(orderId, v, 2000).andExpect(status().isCreated()).andExpect(jsonPath("$.quota.imageUsed").value(5));
    }

    @Test
    @DisplayName("PRD-022 AC-2.6 情境 4 GCS 刪除失敗仍 200、額度已釋放（物件留待 FC2 最終清除）")
    void gcsDeleteFailure() throws Exception {
        UUID orderId = runningOrder("PRO", 1);
        UUID v = visits(orderId).get(0);
        byte[] img = testsupport.fulfill.MediaFiles.jpeg(3000);
        UUID u = applyAndPut(orderId, v, img, img, "IMAGE", "image/jpeg", null, null, key());
        completeUpload(orderId, v, u, sitterToken()).andExpect(status().isCreated());
        storage.failDelete = true;
        deleteAs(LOG + "/media/{m}", sitterToken(), orderId, v, mediaOf(u)).andExpect(status().isOk())
                .andExpect(jsonPath("$.quota.imageUsed").value(0));
        assertThat(storage.exists(objectKey(u))).isTrue();
        assertThat(slotStatus(u)).isEqualTo("DISCARDED");
        assertThat(storage.ioInsideTransaction()).isEmpty();
    }
}
