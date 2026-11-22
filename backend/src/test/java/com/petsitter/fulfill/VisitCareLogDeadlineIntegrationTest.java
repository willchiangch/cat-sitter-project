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
import static testsupport.fulfill.MediaFiles.mp4;

@DisplayName("SD-FULFILL VisitCareLogDeadlineIntegrationTest（期限以 receivedAt）")
class VisitCareLogDeadlineIntegrationTest extends CareLogTestBase {

    @Test
    @DisplayName("PRD-022 AC-1.6 情境 1–2 02:59:59 送出 200；03:00:00 暫存 / 申請上傳 / 刪除 / 送出皆 409 DEADLINE_PASSED（deadlineAt），無新物件、日誌仍 DRAFT")
    void boundary() throws Exception {
        UUID orderId = runningOrder("PRO", 2);
        List<UUID> vs = visits(orderId);
        OffsetDateTime completed = OffsetDateTime.parse("2030-01-01T03:00:00Z");
        uploadJpeg(orderId, vs.get(0), 900).andExpect(status().isCreated());
        UUID mediaId = jdbc.queryForObject("SELECT m.id FROM service_report_media m JOIN visit_service_reports r ON r.id = m.report_id WHERE r.visit_id = ?", UUID.class, vs.get(0));
        done(vs.get(0), completed);
        done(vs.get(1), completed);
        draft(orderId, vs.get(1), sitterToken(), "草稿").andExpect(status().isOk());
        OffsetDateTime deadline = completed.plusHours(24);
        freeze(deadline.minusSeconds(1));
        submit(orderId, vs.get(1), sitterToken(), key(), "ok").andExpect(status().isOk());
        freeze(deadline);
        int objects = storage.objectCount();
        draft(orderId, vs.get(0), sitterToken(), "x").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEADLINE_PASSED")).andExpect(jsonPath("$.payload.deadline").value("CARE_LOG_EDIT_ENDS_AT"))
                .andExpect(jsonPath("$.payload.deadlineAt").value("2030-01-02T03:00:00Z"));
        requestUpload(orderId, vs.get(0), sitterToken(), key(), file(jpeg(500), "IMAGE", "image/jpeg", null), null)
                .andExpect(jsonPath("$.code").value("DEADLINE_PASSED"));
        deleteAs(LOG + "/media/{m}", sitterToken(), orderId, vs.get(0), mediaId).andExpect(jsonPath("$.code").value("DEADLINE_PASSED"));
        submit(orderId, vs.get(0), sitterToken(), key(), "x").andExpect(jsonPath("$.code").value("DEADLINE_PASSED"));
        assertThat(storage.objectCount()).isEqualTo(objects);
        assertThat(jdbc.queryForObject("SELECT status FROM visit_service_reports WHERE visit_id = ?", String.class, vs.get(0))).isEqualTo("DRAFT");
        assertThat(count("SELECT count(*) FROM care_log_media_uploads WHERE visit_id = ?", vs.get(0))).isEqualTo(1);
    }

    @Test
    @DisplayName("PRD-022 AC-1.6 情境 3 / 6：期限前 1 秒申請、期限後但槽位內確認 → 201；確認 receivedAt = 申請 + 30 分 → 409 CARE_LOG_UPLOAD_EXPIRED、無媒體；新 key 重新申請 → 409 DEADLINE_PASSED")
    void applyBeforeDeadlineConfirmWithinSlot() throws Exception {
        UUID orderId = runningOrder("PRO", 1);
        UUID v = visits(orderId).get(0);
        OffsetDateTime completed = OffsetDateTime.parse("2030-01-01T03:00:00Z");
        done(v, completed);
        OffsetDateTime applyAt = completed.plusHours(24).minusSeconds(1);
        freeze(applyAt);
        byte[] video = mp4(20, 40_000);
        UUID ok = applyAndPut(orderId, v, video, video, "VIDEO", "video/mp4", 20.0, null, key());
        byte[] video2 = mp4(21, 40_000);
        UUID late = applyAndPut(orderId, v, video2, video2, "VIDEO", "video/mp4", 21.0, null, key());
        freeze(applyAt.plusMinutes(30).minusSeconds(1));
        completeUpload(orderId, v, ok, sitterToken()).andExpect(status().isCreated()).andExpect(jsonPath("$.quota.videoUsed").value(1));
        freeze(applyAt.plusMinutes(30));
        completeUpload(orderId, v, late, sitterToken()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CARE_LOG_UPLOAD_EXPIRED")).andExpect(jsonPath("$.payload.expiresAt").value("2030-01-02T03:29:59Z"));
        assertThat(mediaCount(v)).isEqualTo(1);
        assertThat(slotStatus(late)).isEqualTo("PENDING");
        requestUpload(orderId, v, sitterToken(), key(), file(mp4(22, 40_000), "VIDEO", "video/mp4", 22.0), null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DEADLINE_PASSED"));
    }

    @Test
    @DisplayName("PRD-022 AC-1.6 F18 receivedAt 在槽位到期前、鎖後才過到期（取鎖等待）→ 仍 201（不以鎖後 now 重比）")
    void receivedBeforeExpiryLockAfter() throws Exception {
        UUID orderId = runningOrder("PRO", 1);
        UUID v = visits(orderId).get(0);
        OffsetDateTime applyAt = OffsetDateTime.parse("2030-03-01T00:00:00Z");
        freeze(applyAt);
        byte[] img = jpeg(800);
        UUID u = applyAndPut(orderId, v, img, img, "IMAGE", "image/jpeg", null, null, key());
        freeze(applyAt.plusMinutes(30).minusSeconds(1));
        // 檢驗物件時（交易外）時鐘推進到槽位到期之後，模擬串流 / 等鎖期間跨過 expires_at
        storage.onRead = () -> clock.freezeAt(applyAt.plusMinutes(31).toInstant());
        completeUpload(orderId, v, u, sitterToken()).andExpect(status().isCreated());
        assertThat(slotStatus(u)).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("PRD-022 AC-1.6 情境 4–5：系統逾時 DONE 以其 completed_at 起算（02:09:59 200、02:10:00 409）")
    void systemCompletedVisit() throws Exception {
        UUID orderId = runningOrder("PRO", 2);
        List<UUID> vs = visits(orderId);
        OffsetDateTime completed = OffsetDateTime.parse("2030-01-02T02:10:00Z");
        forceVisit(vs.get(0), "DONE", completed.minusHours(24), completed, "SYSTEM");
        forceVisit(vs.get(1), "DONE", completed.minusHours(24), completed, "SYSTEM");
        draft(orderId, vs.get(0), sitterToken(), "a").andExpect(status().isOk());
        draft(orderId, vs.get(1), sitterToken(), "b").andExpect(status().isOk());
        freeze(completed.plusHours(24).minusSeconds(1));
        readLog(orderId, vs.get(0), sitterToken()).andExpect(jsonPath("$.submittable").value(true))
                .andExpect(jsonPath("$.deadlineAt").value("2030-01-03T02:10:00Z"));
        submit(orderId, vs.get(0), sitterToken(), key(), "ok").andExpect(status().isOk());
        freeze(completed.plusHours(24));
        submit(orderId, vs.get(1), sitterToken(), key(), "ok").andExpect(status().isConflict())
                .andExpect(jsonPath("$.payload.deadlineAt").value("2030-01-03T02:10:00Z"));
    }
}
