package com.petsitter.fulfill;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static testsupport.fulfill.MediaFiles.sha256;

/** 照護日誌整合測試共用（F13–F19）。 */
abstract class VisitCareLogTestBase extends FulfillIntegrationTestBase {

    protected static final String LOG = VISIT + "/care-log";

    /** 快照 {@code tierName} 的訂單（{@code days} 個行程、每日 1 次）→ 主狀態 IN_PROGRESS、第一個行程 IN_PROGRESS。 */
    protected UUID runningOrder(String tierName, int days) throws Exception {
        tier(sitter, tierName);
        LocalDate d = today().plusDays(1);
        LocalDate[] dates = new LocalDate[days];
        for (int i = 0; i < days; i++) {
            dates[i] = d.plusDays(i);
        }
        UUID orderId = confirmedOrder(dates);
        jdbc.update("UPDATE orders SET status = 'IN_PROGRESS' WHERE id = ?", orderId);
        forceVisit(visits(orderId).get(0), "IN_PROGRESS", OffsetDateTime.now(clock).minusHours(1), null, null);
        return orderId;
    }

    protected void done(UUID visitId, OffsetDateTime completedAt) {
        forceVisit(visitId, "DONE", completedAt.minusHours(1), completedAt, "SITTER");
    }

    protected ResultActions draft(UUID o, UUID v, String token, Object text) throws Exception {
        return putJson(LOG + "/draft", token, text instanceof Map<?, ?> ? text : Map.of("text", text), o, v);
    }

    protected ResultActions submit(UUID o, UUID v, String token, String key, Object text) throws Exception {
        return mvc.perform(auth(post(LOG + "/submit", o, v).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(text instanceof Map<?, ?> ? text : Map.of("text", text))), token, key));
    }

    protected ResultActions readLog(UUID o, UUID v, String token) throws Exception {
        return mvc.perform(getAs(LOG, token, o, v));
    }

    protected static Map<String, Object> file(byte[] bytes, String mediaType, String contentType, Double duration) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("mediaType", mediaType);
        f.put("contentType", contentType);
        f.put("sizeBytes", bytes.length);
        f.put("sha256", sha256(bytes));
        if (duration != null) {
            f.put("durationSeconds", duration);
        }
        return f;
    }

    protected ResultActions requestUpload(UUID o, UUID v, String token, String key, Object fileDesc, String caption) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("file", fileDesc);
        if (caption != null) {
            body.put("caption", caption);
        }
        return mvc.perform(auth(post(LOG + "/media-uploads", o, v).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)), token, key));
    }

    protected ResultActions target(UUID o, UUID v, UUID uploadId, String token) throws Exception {
        return mvc.perform(getAs(LOG + "/media-uploads/{u}/target", token, o, v, uploadId));
    }

    protected ResultActions completeUpload(UUID o, UUID v, UUID uploadId, String token) throws Exception {
        return mvc.perform(auth(post(LOG + "/media-uploads/{u}/complete", o, v, uploadId), token, null));
    }

    protected UUID uploadId(ResultActions r) throws Exception {
        return UUID.fromString(body(r).get("uploadId").asString());
    }

    protected String objectKey(UUID uploadId) {
        return jdbc.queryForObject("SELECT object_key FROM care_log_media_uploads WHERE id = ?", String.class, uploadId);
    }

    protected String slotStatus(UUID uploadId) {
        return jdbc.queryForObject("SELECT status FROM care_log_media_uploads WHERE id = ?", String.class, uploadId);
    }

    /** F16 → F17 → 瀏覽器 PUT（fake）→ 回傳 uploadId（尚未 F18）。 */
    protected UUID applyAndPut(UUID o, UUID v, byte[] declared, byte[] actual, String mediaType, String contentType, Double duration,
                               String caption, String key) throws Exception {
        UUID uploadId = uploadId(requestUpload(o, v, sitterToken(), key, file(declared, mediaType, contentType, duration), caption)
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated()));
        target(o, v, uploadId, sitterToken()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        if (actual != null) {
            storage.put(objectKey(uploadId), actual, contentType);
        }
        return uploadId;
    }

    /** 完整三段式上傳，回傳 F18 結果。 */
    protected ResultActions upload(UUID o, UUID v, byte[] bytes, String mediaType, String contentType, Double duration, String caption)
            throws Exception {
        UUID uploadId = applyAndPut(o, v, bytes, bytes, mediaType, contentType, duration, caption, key());
        return completeUpload(o, v, uploadId, sitterToken());
    }

    protected ResultActions uploadJpeg(UUID o, UUID v, int size) throws Exception {
        return upload(o, v, testsupport.fulfill.MediaFiles.jpeg(size), "IMAGE", "image/jpeg", null, null);
    }

    protected int mediaCount(UUID visitId) {
        return count("SELECT count(*) FROM service_report_media m JOIN visit_service_reports r ON r.id = m.report_id WHERE r.visit_id = ?", visitId);
    }

    protected List<String> uploadStatuses(UUID visitId) {
        return new ArrayList<>(jdbc.queryForList("SELECT status FROM care_log_media_uploads WHERE visit_id = ? ORDER BY created_at", String.class, visitId));
    }
}
