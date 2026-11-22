package com.petsitter.fulfill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static testsupport.fulfill.MediaFiles.jpeg;

@DisplayName("SD-FULFILL VisitCareLogIdempotencyIntegrationTest（F15 / F16）")
class VisitCareLogIdempotencyIntegrationTest extends VisitCareLogTestBase {

    @Test
    @DisplayName("PRD-022 AC-3.5 送出回放同一 body、不重發通知 / log；同 key 不同文字 422；新 key 409 CARE_LOG_STATE_CONFLICT；缺 key 400")
    void submitReplay() throws Exception {
        UUID orderId = runningOrder("PRO", 1);
        UUID v = visits(orderId).get(0);
        done(v, OffsetDateTime.now(clock).minusMinutes(1));
        submit(orderId, v, sitterToken(), null, "ok").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.payload.field").value("Idempotency-Key"));
        String k = key();
        JsonNode first = body(submit(orderId, v, sitterToken(), k, "ok").andExpect(status().isOk()));
        assertThat(body(submit(orderId, v, sitterToken(), k, "ok").andExpect(status().isOk()))).isEqualTo(first);
        assertThat(logCount(orderId, "CARE_LOG_SUBMITTED")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM notifications WHERE user_id = ? AND type = 'CARE_LOG_SUBMITTED'", owner)).isEqualTo(1);
        submit(orderId, v, sitterToken(), k, "不同").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        submit(orderId, v, sitterToken(), key(), "ok").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CARE_LOG_STATE_CONFLICT"));
    }

    @Test
    @DisplayName("PRD-022 AC-3.5 情境 3 / AC-3.6（EX-16）F15、F16 同 key 第一個請求處理中 → 409 IDEMPOTENCY_IN_PROGRESS")
    void inProgress() throws Exception {
        UUID orderId = runningOrder("PRO", 1);
        UUID v = visits(orderId).get(0);
        done(v, OffsetDateTime.now(clock).minusMinutes(1));
        String k = key();
        var hold = holdOrderLock(orderId, Duration.ofSeconds(2));
        var first = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                return submit(orderId, v, sitterToken(), k, "ok").andReturn().getResponse().getStatus();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        Thread.sleep(500);
        submit(orderId, v, sitterToken(), k, "ok").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_IN_PROGRESS"));
        hold.join();
        assertThat(first.join()).isEqualTo(200);

        // F16 不取訂單鎖：以處理中的冪等紀錄（locked_until 未過）模擬第一個請求仍在進行
        UUID orderId2 = runningOrder("PRO", 1);
        UUID v2 = visits(orderId2).get(0);
        String k2 = key();
        var f = file(jpeg(100), "IMAGE", "image/jpeg", null);
        jdbc.execute("CREATE OR REPLACE FUNCTION fulfill_test_slow() RETURNS trigger AS $$ BEGIN PERFORM pg_sleep(2); RETURN NEW; END $$ LANGUAGE plpgsql");
        jdbc.execute("CREATE TRIGGER fulfill_test_slow_trg BEFORE INSERT ON care_log_media_uploads FOR EACH ROW EXECUTE FUNCTION fulfill_test_slow()");
        try {
            var slow = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    return requestUpload(orderId2, v2, sitterToken(), k2, f, null).andReturn().getResponse().getStatus();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            Thread.sleep(700);
            requestUpload(orderId2, v2, sitterToken(), k2, f, null).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("IDEMPOTENCY_IN_PROGRESS"));
            assertThat(slow.join()).isEqualTo(201);
        } finally {
            jdbc.execute("DROP TRIGGER fulfill_test_slow_trg ON care_log_media_uploads");
            jdbc.execute("DROP FUNCTION fulfill_test_slow()");
        }
    }

    @Test
    @DisplayName("PRD-022 AC-3.6 上傳同 key 同內容：F16 回放同一槽位、F17 alreadyCompleted、F18 201 同一媒體（不重傳、物件 1 個、額度 1）；同 key 換檔案 422；完成後超過 31 分鐘且期限已過仍回放 201")
    void uploadReplay() throws Exception {
        UUID orderId = runningOrder("PRO", 1);
        UUID v = visits(orderId).get(0);
        OffsetDateTime at = OffsetDateTime.parse("2030-02-01T00:00:00Z");
        freeze(at);
        byte[] img = jpeg(2000);
        String k = key();
        UUID u = applyAndPut(orderId, v, img, img, "IMAGE", "image/jpeg", null, "說明", k);
        JsonNode created = body(completeUpload(orderId, v, u, sitterToken()).andExpect(status().isCreated()));
        assertThat(uploadId(requestUpload(orderId, v, sitterToken(), k, file(img, "IMAGE", "image/jpeg", null), "說明"))).isEqualTo(u);
        target(orderId, v, u, sitterToken()).andExpect(jsonPath("$.alreadyCompleted").value(true));
        assertThat(body(completeUpload(orderId, v, u, sitterToken()).andExpect(status().isCreated())).get("mediaId")).isEqualTo(created.get("mediaId"));
        assertThat(mediaCount(v)).isEqualTo(1);
        assertThat(storage.listObjectKeys("pro/")).hasSize(1);
        requestUpload(orderId, v, sitterToken(), k, file(jpeg(2001), "IMAGE", "image/jpeg", null), "說明")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        // 期限已過（completed_at + 24h = at + 1h）且距申請超過 31 分鐘，仍在冪等 TTL 24h 內
        done(v, at.minusHours(23));
        freeze(at.plusHours(1).plusMinutes(31));
        assertThat(uploadId(requestUpload(orderId, v, sitterToken(), k, file(img, "IMAGE", "image/jpeg", null), "說明")
                .andExpect(status().isCreated()))).isEqualTo(u);
        target(orderId, v, u, sitterToken()).andExpect(jsonPath("$.alreadyCompleted").value(true));
        completeUpload(orderId, v, u, sitterToken()).andExpect(status().isCreated()).andExpect(jsonPath("$.quota.imageUsed").value(1));
    }
}
