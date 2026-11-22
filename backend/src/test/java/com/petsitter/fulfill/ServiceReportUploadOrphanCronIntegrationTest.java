package com.petsitter.fulfill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static testsupport.fulfill.MediaFiles.jpeg;

@DisplayName("SD-FULFILL ServiceReportUploadOrphanCronIntegrationTest（FC2）")
class ServiceReportUploadOrphanCronIntegrationTest extends VisitCareLogTestBase {

    private static final String JOB = "storage/care-log-orphans";
    private static final OffsetDateTime T0 = OffsetDateTime.parse("2030-06-01T00:00:00Z");

    private OffsetDateTime deletedAt(UUID uploadId) {
        return jdbc.queryForObject("SELECT object_deleted_at FROM care_log_media_uploads WHERE id = ?", OffsetDateTime.class, uploadId);
    }

    @Test
    @DisplayName("共用（FC2）逾時槽位在 expires_at + 寬限後才轉 EXPIRED 並最終清除（寫 object_deleted_at）；寬限前不動；重跑冪等")
    void expireAfterGrace() throws Exception {
        UUID orderId = runningOrder("PRO", 1);
        UUID v = visits(orderId).get(0);
        freeze(T0);
        byte[] img = jpeg(1000);
        UUID u = applyAndPut(orderId, v, img, img, "IMAGE", "image/jpeg", null, null, key());
        freeze(T0.plusMinutes(30 + 15).minusSeconds(1));
        counts(cron(JOB), 0, 0, 0);
        assertThat(slotStatus(u)).isEqualTo("PENDING");
        freeze(T0.plusMinutes(30 + 15));
        counts(cron(JOB), 2, 0, 0);
        assertThat(slotStatus(u)).isEqualTo("EXPIRED");
        assertThat(storage.exists(objectKey(u))).isFalse();
        assertThat(deletedAt(u)).isNotNull();
        counts(cron(JOB), 0, 0, 0);
        completeUpload(orderId, v, u, sitterToken()).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("共用（FC2）F18 在 expires_at 前收到、串流期間 FC2 觸發（未過寬限）→ 不轉 EXPIRED、F18 仍 201")
    void streamingConfirmNotStolen() throws Exception {
        UUID orderId = runningOrder("PRO", 1);
        UUID v = visits(orderId).get(0);
        freeze(T0);
        byte[] img = jpeg(1000);
        UUID u = applyAndPut(orderId, v, img, img, "IMAGE", "image/jpeg", null, null, key());
        freeze(T0.plusMinutes(30).minusSeconds(1));
        int[] expiredDuringStream = new int[1];
        storage.onRead = () -> {
            clock.freezeAt(T0.plusMinutes(31).toInstant());
            expiredDuringStream[0] = orphanCron.run(null).processed();
        };
        completeUpload(orderId, v, u, sitterToken()).andExpect(status().isCreated());
        assertThat(slotStatus(u)).isEqualTo("COMPLETED");
        assertThat(expiredDuringStream[0]).isZero();
    }

    @org.springframework.beans.factory.annotation.Autowired
    private com.petsitter.application.service.fulfill.ServiceReportUploadOrphanCronService orphanCron;

    @Test
    @DisplayName("共用（FC2）提早刪除後被遲到 PUT 重寫的物件於最終清除時刪掉；DISCARDED（F19 刪檔失敗）亦於寬限後清除；COMPLETED 不動")
    void finalCleanupOfFailedAndDiscarded() throws Exception {
        UUID orderId = runningOrder("PRO", 1);
        UUID v = visits(orderId).get(0);
        freeze(T0);
        byte[] img = jpeg(1000);
        UUID failed = applyAndPut(orderId, v, img, jpeg(1001), "IMAGE", "image/jpeg", null, null, key());
        completeUpload(orderId, v, failed, sitterToken()).andExpect(status().isBadRequest());
        assertThat(slotStatus(failed)).isEqualTo("FAILED");
        assertThat(storage.exists(objectKey(failed))).isFalse();
        storage.overwrite(objectKey(failed), jpeg(1002)); // 遲到的 PUT
        UUID discarded = applyAndPut(orderId, v, img, img, "IMAGE", "image/jpeg", null, null, key());
        completeUpload(orderId, v, discarded, sitterToken()).andExpect(status().isCreated());
        UUID kept = applyAndPut(orderId, v, jpeg(1003), jpeg(1003), "IMAGE", "image/jpeg", null, null, key());
        completeUpload(orderId, v, kept, sitterToken()).andExpect(status().isCreated());
        storage.failDelete = true;
        UUID mediaId = jdbc.queryForObject("SELECT id FROM service_report_media WHERE upload_id = ?", UUID.class, discarded);
        deleteAs(LOG + "/media/{m}", sitterToken(), orderId, v, mediaId).andExpect(status().isOk());
        storage.failDelete = false;
        freeze(T0.plusMinutes(45));
        counts(cron(JOB), 2, 0, 0);
        assertThat(storage.exists(objectKey(failed))).isFalse();
        assertThat(storage.exists(objectKey(discarded))).isFalse();
        assertThat(deletedAt(failed)).isNotNull();
        assertThat(deletedAt(discarded)).isNotNull();
        assertThat(storage.exists(objectKey(kept))).isTrue();
        assertThat(deletedAt(kept)).isNull();
    }

    @Test
    @DisplayName("共用（FC2）刪除持續失敗 delete_attempts + 1（達 5 次起記 ERROR）、每輪重試；orderId 只處理該測試帳號訂單、自動觸發跳過測試帳號")
    void deleteFailuresAndScope() throws Exception {
        UUID orderId = runningOrder("PRO", 1);
        UUID v = visits(orderId).get(0);
        freeze(T0);
        byte[] img = jpeg(1000);
        UUID u = applyAndPut(orderId, v, img, img, "IMAGE", "image/jpeg", null, null, key());
        freeze(T0.plusMinutes(45));
        storage.failDelete = true;
        counts(cron(JOB), 1, 0, 1);
        for (int i = 0; i < 5; i++) {
            counts(cron(JOB), 0, 0, 1);
        }
        assertThat(jdbc.queryForObject("SELECT delete_attempts FROM care_log_media_uploads WHERE id = ?", Integer.class, u)).isEqualTo(6);
        storage.failDelete = false;
        counts(cron(JOB), 1, 0, 0);

        clock.reset();
        UUID testOrder = testAccountConfirmedOrder(today().plusDays(1));
        jdbc.update("UPDATE orders SET status = 'IN_PROGRESS' WHERE id = ?", testOrder);
        UUID tv = visits(testOrder).get(0);
        forceVisit(tv, "IN_PROGRESS", OffsetDateTime.now(clock), null, null);
        freeze(T0);
        UUID tu = applyAndPut(testOrder, tv, img, img, "IMAGE", "image/jpeg", null, null, key());
        freeze(T0.plusMinutes(45));
        counts(cron(JOB), 0, 0, 0);
        assertThat(slotStatus(tu)).isEqualTo("PENDING");
        cronRaw(JOB, "orderId=" + orderId).andExpect(status().isNotFound());
        counts(body(cronRaw(JOB, "orderId=" + testOrder).andExpect(status().isOk())), 2, 0, 0);
        assertThat(slotStatus(tu)).isEqualTo("EXPIRED");
    }
}
