package com.petsitter.fulfill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("SD-FULFILL VisitCareLogReadIntegrationTest（F13）")
class VisitCareLogReadIntegrationTest extends VisitCareLogTestBase {

    @Test
    @DisplayName("PRD-022 AC-1.7 逾期草稿：保母可讀（expired=true、不可編輯 / 送出、含文字與照片）；飼主讀取 404、無日誌通知")
    void expiredDraft() throws Exception {
        UUID orderId = runningOrder("PRO", 1);
        UUID v = visits(orderId).get(0);
        draft(orderId, v, sitterToken(), "乾乾吃完").andExpect(status().isOk());
        uploadJpeg(orderId, v, 1000).andExpect(status().isCreated());
        uploadJpeg(orderId, v, 1001).andExpect(status().isCreated());
        OffsetDateTime completed = OffsetDateTime.now(clock).minusMinutes(1);
        done(v, completed);
        freeze(completed.plusHours(24));
        readLog(orderId, v, sitterToken()).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.expired").value(true)).andExpect(jsonPath("$.editable").value(false))
                .andExpect(jsonPath("$.submittable").value(false)).andExpect(jsonPath("$.text").value("乾乾吃完"))
                .andExpect(jsonPath("$.media.length()").value(2));
        readLog(orderId, v, ownerToken()).andExpect(status().isNotFound());
        assertThat(count("SELECT count(*) FROM notifications WHERE user_id = ? AND type = 'CARE_LOG_SUBMITTED'", owner)).isZero();
    }

    @Test
    @DisplayName("PRD-022 AC-3.2 飼主檢視已送出日誌（名稱、文字、媒體與說明）、簽名網址 X-Goog-Expires=900 且含 response-content-type（判定格式）與 response-content-disposition=inline、快取命中時 urlExpiresAt 為原值；DRAFT 日誌 404；purged 不給網址；預設名稱")
    void ownerView() throws Exception {
        UUID orderId = runningOrder("PRO", 2);
        List<UUID> vs = visits(orderId);
        upload(orderId, vs.get(0), testsupport.fulfill.MediaFiles.jpeg(1000), "IMAGE", "image/jpeg", null, "睡午覺").andExpect(status().isCreated());
        upload(orderId, vs.get(0), testsupport.fulfill.MediaFiles.jpeg(1001), "IMAGE", "image/jpeg", null, null).andExpect(status().isCreated());
        OffsetDateTime completed = OffsetDateTime.now(clock).minusMinutes(1);
        done(vs.get(0), completed);
        rename(orderId, vs.get(0), sitterToken(), "早上服務").andExpect(status().isOk());
        submit(orderId, vs.get(0), sitterToken(), key(), "很乖").andExpect(status().isOk());
        forceVisit(vs.get(1), "IN_PROGRESS", completed, null, null);
        draft(orderId, vs.get(1), sitterToken(), "草稿").andExpect(status().isOk());
        JsonNode r = body(readLog(orderId, vs.get(0), ownerToken()).andExpect(status().isOk()));
        assertThat(r.get("visitName").asString()).isEqualTo("早上服務");
        assertThat(r.get("text").asString()).isEqualTo("很乖");
        assertThat(r.has("status")).isFalse();
        assertThat(r.has("quota")).isFalse();
        assertThat(r.get("media").get(0).get("caption").asString()).isEqualTo("睡午覺");
        String url = r.get("media").get(0).get("url").asString();
        assertThat(url).contains("X-Goog-Expires=900").contains("response-content-type=image%2Fjpeg")
                .contains("response-content-disposition=inline");
        String expiresAt = r.get("media").get(0).get("urlExpiresAt").asString();
        int signs = storage.signReadCalls.get();
        advance(java.time.Duration.ofMinutes(2));
        JsonNode again = body(readLog(orderId, vs.get(0), ownerToken()));
        assertThat(again.get("media").get(0).get("urlExpiresAt").asString()).isEqualTo(expiresAt);
        assertThat(again.get("media").get(0).get("url").asString()).isEqualTo(url);
        assertThat(storage.signReadCalls.get()).isEqualTo(signs);
        readLog(orderId, vs.get(1), ownerToken()).andExpect(status().isNotFound());
        jdbc.update("UPDATE service_report_media SET is_purged = true, purged_at = now() WHERE caption = '睡午覺'");
        JsonNode purged = body(readLog(orderId, vs.get(0), ownerToken()));
        assertThat(purged.get("media").get(0).get("purged").asBoolean()).isTrue();
        assertThat(purged.get("media").get(0).has("url")).isFalse();
        setOrderStatus(orderId, "COMPLETED");
        readLog(orderId, vs.get(0), ownerToken()).andExpect(status().isOk());
        rename(orderId, vs.get(0), sitterToken(), "").andExpect(status().isConflict());
        jdbc.update("UPDATE visits SET custom_name = NULL WHERE id = ?", vs.get(0));
        readLog(orderId, vs.get(0), ownerToken()).andExpect(jsonPath("$.visitName").value("到府餵食"));
    }

    @Test
    @DisplayName("PRD-022 AC-3.4 ADMIN 唯讀：DISPUTED 訂單讀已送出 200、DRAFT 404；從未有待辦的 IN_PROGRESS 訂單 403")
    void adminRead() throws Exception {
        UUID orderId = runningOrder("PRO", 2);
        List<UUID> vs = visits(orderId);
        OffsetDateTime completed = OffsetDateTime.now(clock).minusMinutes(1);
        done(vs.get(0), completed);
        submit(orderId, vs.get(0), sitterToken(), key(), "ok").andExpect(status().isOk());
        forceVisit(vs.get(1), "IN_PROGRESS", completed, null, null);
        draft(orderId, vs.get(1), sitterToken(), "草稿").andExpect(status().isOk());
        readLog(orderId, vs.get(0), adminToken()).andExpect(status().isForbidden());
        setOrderStatus(orderId, "DISPUTED");
        readLog(orderId, vs.get(0), adminToken()).andExpect(status().isOk()).andExpect(jsonPath("$.editable").doesNotExist());
        readLog(orderId, vs.get(1), adminToken()).andExpect(status().isNotFound());
    }
}
