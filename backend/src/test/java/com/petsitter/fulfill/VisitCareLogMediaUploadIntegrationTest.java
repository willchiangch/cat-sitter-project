package com.petsitter.fulfill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static testsupport.fulfill.MediaFiles.*;

@DisplayName("SD-FULFILL VisitCareLogMediaUploadIntegrationTest（F16–F18）")
class VisitCareLogMediaUploadIntegrationTest extends CareLogTestBase {

    @Test
    @DisplayName("PRD-022 AC-2.1 FREE 快照（保母已升級 ULTIMATE）：申請上傳 422 QUOTA_EXCEEDED（limit 0、used 0），無槽位、無物件、無日誌；暫存 200")
    void freeSnapshot() throws Exception {
        UUID orderId = runningOrder("FREE", 1);
        UUID v = visits(orderId).get(0);
        tier(sitter, "ULTIMATE");
        requestUpload(orderId, v, sitterToken(), key(), file(jpeg(500_000), "IMAGE", "image/jpeg", null), null)
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("CARE_LOG_MEDIA_QUOTA_EXCEEDED"))
                .andExpect(jsonPath("$.payload.mediaType").value("IMAGE")).andExpect(jsonPath("$.payload.limit").value(0))
                .andExpect(jsonPath("$.payload.used").value(0));
        assertThat(count("SELECT count(*) FROM care_log_media_uploads")).isZero();
        assertThat(storage.listObjectKeys("free/")).isEmpty();
        assertThat(count("SELECT count(*) FROM visit_service_reports")).isZero();
        draft(orderId, v, sitterToken(), "文字").andExpect(status().isOk());
        readLog(orderId, v, sitterToken()).andExpect(jsonPath("$.quota.planTier").value("FREE")).andExpect(jsonPath("$.quota.imageLimit").value(0));
    }

    @Test
    @DisplayName("PRD-022 AC-2.2 額度依快照：BASIC 5 張後第 6 張 422（used 5）、影片 limit 0；PRO 快照降級 FREE 仍 10 + 2")
    void quotaBySnapshot() throws Exception {
        UUID basic = runningOrder("BASIC", 1);
        UUID v = visits(basic).get(0);
        for (int i = 0; i < 5; i++) {
            uploadJpeg(basic, v, 1000 + i).andExpect(status().isCreated());
        }
        readLog(basic, v, sitterToken()).andExpect(jsonPath("$.quota.imageUsed").value(5)).andExpect(jsonPath("$.quota.imageLimit").value(5));
        requestUpload(basic, v, sitterToken(), key(), file(jpeg(900), "IMAGE", "image/jpeg", null), null)
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.payload.limit").value(5)).andExpect(jsonPath("$.payload.used").value(5));
        requestUpload(basic, v, sitterToken(), key(), file(mp4(20, 5000), "VIDEO", "video/mp4", 20.0), null)
                .andExpect(jsonPath("$.payload.mediaType").value("VIDEO")).andExpect(jsonPath("$.payload.limit").value(0));

        UUID pro = runningOrder("PRO", 1);
        UUID pv = visits(pro).get(0);
        tier(sitter, "FREE");
        for (int i = 0; i < 2; i++) {
            upload(pro, pv, mp4(20, 5000 + i), "VIDEO", "video/mp4", 20.0, null).andExpect(status().isCreated());
        }
        requestUpload(pro, pv, sitterToken(), key(), file(mp4(20, 6000), "VIDEO", "video/mp4", 20.0), null)
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.payload.limit").value(2));
    }

    @Test
    @DisplayName("PRD-022 AC-2.3 照片：JPEG / PNG / WebP 201；宣告 GIF / HEIC 422 TYPE_UNSUPPORTED；宣告 1,048,576 422 TOO_LARGE、1,048,575 201；實際 GIF 或宣告 JPEG 實為 PNG 422；實際 1,048,576 422（宣告雜湊等於實際檔案）；拒絕者物件被刪、無媒體")
    void photoFormats() throws Exception {
        UUID orderId = runningOrder("ULTIMATE", 1);
        UUID v = visits(orderId).get(0);
        upload(orderId, v, jpeg(900_000), "IMAGE", "image/jpeg", null, null).andExpect(status().isCreated());
        upload(orderId, v, png(900_000), "IMAGE", "image/png", null, null).andExpect(status().isCreated());
        upload(orderId, v, webp(900_000), "IMAGE", "image/webp", null, null).andExpect(status().isCreated());
        for (String ct : List.of("image/gif", "image/heic")) {
            requestUpload(orderId, v, sitterToken(), key(), file(gif(900), "IMAGE", ct, null), null)
                    .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("CARE_LOG_MEDIA_TYPE_UNSUPPORTED"));
        }
        requestUpload(orderId, v, sitterToken(), key(), file(jpeg(1_048_576), "IMAGE", "image/jpeg", null), null)
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("CARE_LOG_MEDIA_TOO_LARGE"))
                .andExpect(jsonPath("$.payload.maxBytes").value(1_048_576));
        upload(orderId, v, jpeg(1_048_575), "IMAGE", "image/jpeg", null, null).andExpect(status().isCreated());
        int before = mediaCount(v);

        // 實際內容檢驗（宣告雜湊與實際檔案一致，以 fake 直接放置物件造出）
        byte[] actualGif = gif(900_000);
        UUID g = applyAndPut(orderId, v, actualGif, actualGif, "IMAGE", "image/jpeg", null, null, key());
        completeUpload(orderId, v, g, sitterToken()).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CARE_LOG_MEDIA_TYPE_UNSUPPORTED"));
        byte[] actualPng = png(900_000);
        UUID p = applyAndPut(orderId, v, actualPng, actualPng, "IMAGE", "image/jpeg", null, null, key());
        completeUpload(orderId, v, p, sitterToken()).andExpect(jsonPath("$.code").value("CARE_LOG_MEDIA_TYPE_UNSUPPORTED"));
        byte[] declared = jpeg(900_000);
        byte[] big = jpeg(1_048_576);
        Map<String, Object> desc = file(declared, "IMAGE", "image/jpeg", null);
        desc.put("sha256", sha256(big));
        UUID t = uploadId(requestUpload(orderId, v, sitterToken(), key(), desc, null).andExpect(status().isCreated()));
        storage.put(objectKey(t), big, "image/jpeg");
        completeUpload(orderId, v, t, sitterToken()).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CARE_LOG_MEDIA_TOO_LARGE"));
        for (UUID u : List.of(g, p, t)) {
            assertThat(slotStatus(u)).isEqualTo("FAILED");
            assertThat(storage.exists(objectKey(u))).isFalse();
        }
        assertThat(mediaCount(v)).isEqualTo(before);
        assertThat(storage.ioInsideTransaction()).isEmpty();
    }

    @Test
    @DisplayName("PRD-022 AC-2.4 影片：MP4 15 秒、MOV 30 秒、MP4 20 秒 201；宣告 14 / 31 秒 422 DURATION_INVALID（min/max/actual）；宣告 52,428,800 422；WebM 422；實際 14 秒 422 且物件刪除")
    void videoFormats() throws Exception {
        UUID orderId = runningOrder("ULTIMATE", 1);
        UUID v = visits(orderId).get(0);
        upload(orderId, v, mp4(15, 20_000), "VIDEO", "video/mp4", 15.0, null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.durationSeconds").value(15));
        upload(orderId, v, mov(30, 20_000), "VIDEO", "video/quicktime", 30.0, null).andExpect(status().isCreated());
        upload(orderId, v, mp4(20, 30_000), "VIDEO", "video/mp4", 20.4, null).andExpect(status().isCreated());
        requestUpload(orderId, v, sitterToken(), key(), file(mp4(14, 1000), "VIDEO", "video/mp4", 14.0), null)
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("CARE_LOG_VIDEO_DURATION_INVALID"))
                .andExpect(jsonPath("$.payload.minSeconds").value(15)).andExpect(jsonPath("$.payload.maxSeconds").value(30))
                .andExpect(jsonPath("$.payload.actualSeconds").value(14));
        requestUpload(orderId, v, sitterToken(), key(), file(mp4(31, 1000), "VIDEO", "video/mp4", 30.5), null)
                .andExpect(jsonPath("$.payload.actualSeconds").value(31));
        Map<String, Object> huge = file(mp4(20, 1000), "VIDEO", "video/mp4", 20.0);
        huge.put("sizeBytes", 52_428_800);
        requestUpload(orderId, v, sitterToken(), key(), huge, null).andExpect(jsonPath("$.code").value("CARE_LOG_MEDIA_TOO_LARGE"));
        requestUpload(orderId, v, sitterToken(), key(), file(mp4(20, 1000), "VIDEO", "video/webm", 20.0), null)
                .andExpect(jsonPath("$.code").value("CARE_LOG_MEDIA_TYPE_UNSUPPORTED"));
        requestUpload(orderId, v, sitterToken(), key(), file(mp4(20, 1000), "VIDEO", "video/mp4", null), null)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.payload.field").value("file.durationSeconds"));
        byte[] actual14 = mp4(14, 2000);
        UUID u = applyAndPut(orderId, v, actual14, actual14, "VIDEO", "video/mp4", 20.0, null, key());
        completeUpload(orderId, v, u, sitterToken()).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CARE_LOG_VIDEO_DURATION_INVALID")).andExpect(jsonPath("$.payload.actualSeconds").value(14));
        assertThat(storage.exists(objectKey(u))).isFalse();
        assertThat(mediaCount(v)).isEqualTo(3);
    }

    @Test
    @DisplayName("PRD-022 AC-2.5 上傳成功：F18 201（mediaId、說明、已用量）、首次上傳建 DRAFT、MEDIA_UPLOADED payload；只完成申請與傳檔時已用量仍 0；說明 101 字 / 無 file / file 為陣列 400 且無槽位")
    void uploadSuccess() throws Exception {
        UUID orderId = runningOrder("BASIC", 1);
        UUID v = visits(orderId).get(0);
        byte[] img = webp(800_000);
        UUID u = applyAndPut(orderId, v, img, img, "IMAGE", "image/webp", null, "睡午覺", key());
        readLog(orderId, v, sitterToken()).andExpect(jsonPath("$.quota.imageUsed").value(0));
        JsonNode r = body(completeUpload(orderId, v, u, sitterToken()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.mediaType").value("IMAGE")).andExpect(jsonPath("$.caption").value("睡午覺"))
                .andExpect(jsonPath("$.quota.imageUsed").value(1)).andExpect(jsonPath("$.quota.imageLimit").value(5)));
        assertThat(r.has("url")).isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM visit_service_reports WHERE visit_id = ?", String.class, v)).isEqualTo("DRAFT");
        Map<String, Object> log = jdbc.queryForMap("SELECT payload->>'objectKey' AS k, payload->>'mediaType' AS t, payload->>'sizeBytes' AS s, "
                + "payload->>'careLogCreated' AS c, payload->>'uploadId' AS u FROM order_logs WHERE order_id = ? AND action_type = 'MEDIA_UPLOADED'", orderId);
        assertThat(log).containsEntry("k", objectKey(u)).containsEntry("t", "IMAGE").containsEntry("s", "800000").containsEntry("c", "true")
                .containsEntry("u", u.toString());
        assertThat(objectKey(u)).startsWith("basic/").endsWith("/" + orderId + "/" + u + ".webp");
        assertThat(jdbc.queryForObject("SELECT object_generation FROM service_report_media WHERE upload_id = ?", Long.class, u)).isPositive();
        int slots = count("SELECT count(*) FROM care_log_media_uploads");
        requestUpload(orderId, v, sitterToken(), key(), file(img, "IMAGE", "image/webp", null), "字".repeat(101))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.payload.field").value("caption"));
        requestUpload(orderId, v, sitterToken(), key(), null, null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.payload.field").value("file"));
        requestUpload(orderId, v, sitterToken(), key(), List.of(file(img, "IMAGE", "image/webp", null), file(img, "IMAGE", "image/webp", null)), null)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.payload.field").value("file"));
        assertThat(count("SELECT count(*) FROM care_log_media_uploads")).isEqualTo(slots);
    }

    @Test
    @DisplayName("PRD-022 AC-2.8 F17 簽名失敗 503、同 key 重送回放同一槽位後重試成功；F18 讀取失敗 503（槽位 PENDING、物件保留）同槽位重打 201；OBJECT_MISSING / SHA256_MISMATCH 400；O15 介入後鎖內重驗 409 且物件刪除")
    void storageFailuresAndStateChange() throws Exception {
        UUID orderId = runningOrder("PRO", 1);
        UUID v = visits(orderId).get(0);
        byte[] img = jpeg(5000);
        String k = key();
        UUID u = uploadId(requestUpload(orderId, v, sitterToken(), k, file(img, "IMAGE", "image/jpeg", null), null).andExpect(status().isCreated()));
        storage.failSign = true;
        target(orderId, v, u, sitterToken()).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("STORAGE_UNAVAILABLE"));
        storage.failSign = false;
        assertThat(uploadId(requestUpload(orderId, v, sitterToken(), k, file(img, "IMAGE", "image/jpeg", null), null))).isEqualTo(u);
        assertThat(mediaCount(v)).isZero();
        completeUpload(orderId, v, u, sitterToken()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.payload.field").value("file")).andExpect(jsonPath("$.payload.reason").value("OBJECT_MISSING"));
        assertThat(slotStatus(u)).isEqualTo("PENDING");
        target(orderId, v, u, sitterToken()).andExpect(status().isOk());
        storage.put(objectKey(u), img, "image/jpeg");
        storage.failRead = true;
        completeUpload(orderId, v, u, sitterToken()).andExpect(status().isServiceUnavailable());
        assertThat(slotStatus(u)).isEqualTo("PENDING");
        assertThat(storage.exists(objectKey(u))).isTrue();
        storage.failRead = false;
        completeUpload(orderId, v, u, sitterToken()).andExpect(status().isCreated());
        assertThat(mediaCount(v)).isEqualTo(1);

        byte[] other = jpeg(6000);
        UUID mismatch = applyAndPut(orderId, v, img, other, "IMAGE", "image/jpeg", null, null, key());
        completeUpload(orderId, v, mismatch, sitterToken()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.payload.reason").value("SHA256_MISMATCH"));
        assertThat(slotStatus(mismatch)).isEqualTo("FAILED");
        assertThat(storage.exists(objectKey(mismatch))).isFalse();
        completeUpload(orderId, v, mismatch, sitterToken()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.payload.reason").value("SHA256_MISMATCH"));
        target(orderId, v, mismatch, sitterToken()).andExpect(status().isBadRequest());

        // O15 介入：鎖外預檢通過、檢驗後交易 B 前訂單轉 COMPLETED → 409 ORDER_STATE_CONFLICT、物件刪除、無媒體
        done(v, OffsetDateTime.now(clock).minusMinutes(1));
        setOrderStatus(orderId, "DISPUTED");
        byte[] img3 = jpeg(7000);
        UUID u3 = applyAndPut(orderId, v, img3, img3, "IMAGE", "image/jpeg", null, null, key());
        storage.onRead = () -> setOrderStatus(orderId, "COMPLETED");
        completeUpload(orderId, v, u3, sitterToken()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_STATE_CONFLICT")).andExpect(jsonPath("$.payload.currentStatus").value("COMPLETED"));
        assertThat(slotStatus(u3)).isEqualTo("FAILED");
        assertThat(storage.exists(objectKey(u3))).isFalse();
        assertThat(mediaCount(v)).isEqualTo(1);
        assertThat(storage.ioInsideTransaction()).isEmpty();
    }

    @Test
    @DisplayName("PRD-022 AC-2.8（§3.3）F17：TTL = min(15 分, expires_at − now)、到期 409 UPLOAD_EXPIRED；COMPLETED 先於逾期回 alreadyCompleted；簽名綁定大小與 if-generation-match=0")
    void targetTtl() throws Exception {
        UUID orderId = runningOrder("PRO", 1);
        UUID v = visits(orderId).get(0);
        OffsetDateTime at = OffsetDateTime.parse("2030-05-01T00:00:00Z");
        freeze(at);
        byte[] img = jpeg(4321);
        UUID u = uploadId(requestUpload(orderId, v, sitterToken(), key(), file(img, "IMAGE", "image/jpeg", null), null));
        target(orderId, v, u, sitterToken()).andExpect(status().isOk()).andExpect(jsonPath("$.alreadyCompleted").value(false))
                .andExpect(jsonPath("$.method").value("PUT")).andExpect(jsonPath("$.headers['x-goog-content-length-range']").value("4321,4321"))
                .andExpect(jsonPath("$.headers['x-goog-if-generation-match']").value("0"))
                .andExpect(jsonPath("$.urlExpiresAt").value("2030-05-01T00:15:00Z"));
        assertThat(storage.lastUploadTtl).isEqualTo(Duration.ofMinutes(15));
        freeze(at.plusMinutes(25));
        target(orderId, v, u, sitterToken()).andExpect(jsonPath("$.urlExpiresAt").value("2030-05-01T00:30:00Z"));
        assertThat(storage.lastUploadTtl).isEqualTo(Duration.ofMinutes(5));
        storage.put(objectKey(u), img, "image/jpeg");
        freeze(at.plusMinutes(30));
        target(orderId, v, u, sitterToken()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CARE_LOG_UPLOAD_EXPIRED"));
        freeze(at.plusMinutes(10));
        completeUpload(orderId, v, u, sitterToken()).andExpect(status().isCreated());
        freeze(at.plusHours(2));
        target(orderId, v, u, sitterToken()).andExpect(status().isOk()).andExpect(jsonPath("$.alreadyCompleted").value(true))
                .andExpect(jsonPath("$.uploadUrl").doesNotExist());
    }

    @Test
    @DisplayName("PRD-022 AC-2.3（DF7）物件被設為公開時 F18 收回公開讀取")
    void revokePublic() throws Exception {
        UUID orderId = runningOrder("PRO", 1);
        UUID v = visits(orderId).get(0);
        byte[] img = jpeg(1000);
        UUID u = applyAndPut(orderId, v, img, img, "IMAGE", "image/jpeg", null, null, key());
        storage.markPublic(objectKey(u));
        completeUpload(orderId, v, u, sitterToken()).andExpect(status().isCreated());
        assertThat(storage.isPubliclyReadable(objectKey(u))).isFalse();
    }
}
