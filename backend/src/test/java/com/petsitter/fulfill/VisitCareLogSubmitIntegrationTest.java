package com.petsitter.fulfill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("SD-FULFILL VisitCareLogSubmitIntegrationTest（F15）")
class VisitCareLogSubmitIntegrationTest extends VisitCareLogTestBase {

    @Test
    @DisplayName("PRD-022 AC-1.4 IN_PROGRESS 送出 409；DONE 送出 DRAFT → SUBMITTED、CARE_LOG_SUBMITTED 1 筆；FREE 未暫存直接送出建立；空白 422 CARE_LOG_CONTENT_EMPTY")
    void submit() throws Exception {
        UUID orderId = runningOrder("PRO", 2);
        List<UUID> vs = visits(orderId);
        draft(orderId, vs.get(0), sitterToken(), "草稿").andExpect(status().isOk());
        submit(orderId, vs.get(0), sitterToken(), key(), "x").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VISIT_STATE_CONFLICT"));
        OffsetDateTime completed = OffsetDateTime.now(clock).minusMinutes(5);
        done(vs.get(0), completed);
        submit(orderId, vs.get(0), sitterToken(), key(), "   ").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CARE_LOG_CONTENT_EMPTY"));
        assertThat(jdbc.queryForObject("SELECT status FROM visit_service_reports WHERE visit_id = ?", String.class, vs.get(0))).isEqualTo("DRAFT");
        submit(orderId, vs.get(0), sitterToken(), key(), "已餵藥").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED")).andExpect(jsonPath("$.submittedAt").exists());
        assertThat(jdbc.queryForMap("SELECT status, content, submitted_at IS NOT NULL AS s FROM visit_service_reports WHERE visit_id = ?", vs.get(0)))
                .containsEntry("status", "SUBMITTED").containsEntry("content", "已餵藥").containsEntry("s", true);
        assertThat(logCount(orderId, "CARE_LOG_SUBMITTED")).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT payload->>'textLength' AS l, payload->>'imageCount' AS i FROM order_logs WHERE order_id = ? AND action_type = 'CARE_LOG_SUBMITTED'", orderId))
                .containsEntry("l", "3").containsEntry("i", "0");

        UUID free = runningOrder("FREE", 1);
        UUID v3 = visits(free).get(0);
        done(v3, OffsetDateTime.now(clock).minusMinutes(1));
        submit(free, v3, sitterToken(), key(), "一切正常").andExpect(status().isOk());
        assertThat(jdbc.queryForMap("SELECT status, created_by FROM visit_service_reports WHERE visit_id = ?", v3))
                .containsEntry("status", "SUBMITTED").containsEntry("created_by", sitter);
    }

    @Test
    @DisplayName("PRD-022 AC-1.5 送出後唯讀：暫存 / 申請上傳 / 刪除媒體 / 新 key 送出皆 409 CARE_LOG_STATE_CONFLICT；已過期限仍為 CARE_LOG_STATE_CONFLICT")
    void readOnlyAfterSubmit() throws Exception {
        UUID orderId = runningOrder("PRO", 1);
        UUID v = visits(orderId).get(0);
        uploadJpeg(orderId, v, 1000).andExpect(status().isCreated());
        UUID mediaId = jdbc.queryForObject("SELECT m.id FROM service_report_media m JOIN visit_service_reports r ON r.id = m.report_id WHERE r.visit_id = ?", UUID.class, v);
        OffsetDateTime completed = OffsetDateTime.now(clock).minusMinutes(1);
        done(v, completed);
        submit(orderId, v, sitterToken(), key(), "ok").andExpect(status().isOk());
        draft(orderId, v, sitterToken(), "x").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CARE_LOG_STATE_CONFLICT")).andExpect(jsonPath("$.payload.currentCareLogStatus").value("SUBMITTED"));
        requestUpload(orderId, v, sitterToken(), key(), file(testsupport.fulfill.MediaFiles.jpeg(500), "IMAGE", "image/jpeg", null), null)
                .andExpect(jsonPath("$.code").value("CARE_LOG_STATE_CONFLICT"));
        deleteAs(LOG + "/media/{m}", sitterToken(), orderId, v, mediaId).andExpect(jsonPath("$.code").value("CARE_LOG_STATE_CONFLICT"));
        submit(orderId, v, sitterToken(), key(), "again").andExpect(jsonPath("$.code").value("CARE_LOG_STATE_CONFLICT"));
        freeze(completed.plusHours(25));
        draft(orderId, v, sitterToken(), "x").andExpect(jsonPath("$.code").value("CARE_LOG_STATE_CONFLICT"));
        assertThat(mediaCount(v)).isEqualTo(1);
    }

    @Test
    @DisplayName("PRD-022 AC-1.8 DISPUTED 可暫存與送出；訂閱鎖定可暫存 / 上傳 / 送出；COMPLETED（O15 / O11 / O12）一律 ORDER_STATE_CONFLICT（先於日誌狀態與期限）")
    void orderStates() throws Exception {
        UUID orderId = runningOrder("PRO", 3);
        List<UUID> vs = visits(orderId);
        OffsetDateTime completed = OffsetDateTime.now(clock).minusMinutes(5);
        done(vs.get(0), completed);
        setOrderStatus(orderId, "DISPUTED");
        draft(orderId, vs.get(0), sitterToken(), "爭議中").andExpect(status().isOk());
        submit(orderId, vs.get(0), sitterToken(), key(), "爭議中送出").andExpect(status().isOk());
        setOrderStatus(orderId, "IN_PROGRESS");
        tier(sitter, "FREE");
        jdbc.update("UPDATE subscriptions SET expired_at = now() - interval '30 days' WHERE sitter_id = ?", sitter);
        done(vs.get(1), completed);
        draft(orderId, vs.get(1), sitterToken(), "鎖定").andExpect(status().isOk());
        uploadJpeg(orderId, vs.get(1), 1000).andExpect(status().isCreated());
        submit(orderId, vs.get(1), sitterToken(), key(), "鎖定送出").andExpect(status().isOk());
        done(vs.get(2), completed);
        draft(orderId, vs.get(2), sitterToken(), "草稿").andExpect(status().isOk());
        setOrderStatus(orderId, "COMPLETED");
        draft(orderId, vs.get(2), sitterToken(), "x").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_STATE_CONFLICT")).andExpect(jsonPath("$.payload.currentStatus").value("COMPLETED"));
        submit(orderId, vs.get(2), sitterToken(), key(), "x").andExpect(jsonPath("$.code").value("ORDER_STATE_CONFLICT"));
        draft(orderId, vs.get(0), sitterToken(), "x").andExpect(jsonPath("$.code").value("ORDER_STATE_CONFLICT"));
        freeze(completed.plusHours(30));
        draft(orderId, vs.get(2), sitterToken(), "x").andExpect(jsonPath("$.code").value("ORDER_STATE_CONFLICT"));
    }

    @Test
    @DisplayName("PRD-022 AC-3.1 送出通知飼主 1 則（PRD 字面、連結開啟日誌）、Email 依偏好（開 PENDING / 關 SKIPPED_PREFERENCE）；暫存 / 上傳 / 刪除不通知；Email 投遞失敗不影響送出")
    void notification() throws Exception {
        UUID orderId = runningOrder("PRO", 2);
        List<UUID> vs = visits(orderId);
        OffsetDateTime completed = OffsetDateTime.now(clock).minusMinutes(5);
        done(vs.get(0), completed);
        draft(orderId, vs.get(0), sitterToken(), "x").andExpect(status().isOk());
        uploadJpeg(orderId, vs.get(0), 900).andExpect(status().isCreated());
        assertThat(count("SELECT count(*) FROM notifications WHERE user_id = ? AND type = 'CARE_LOG_SUBMITTED'", owner)).isZero();
        rename(orderId, vs.get(0), sitterToken(), "早上服務").andExpect(status().isOk());
        submit(orderId, vs.get(0), sitterToken(), key(), "ok").andExpect(status().isOk());
        Map<String, Object> n = jdbc.queryForMap("SELECT content, link_url, email_status, mandatory FROM notifications WHERE user_id = ? AND type = 'CARE_LOG_SUBMITTED'", owner);
        assertThat(n.get("content")).isEqualTo(serviceDate(vs.get(0)) + " 早上服務 的照護日誌已由保母回報，快來看看毛孩的近況吧！");
        assertThat(n.get("link_url")).isEqualTo("/orders/" + orderId + "/visits/" + vs.get(0) + "/care-log");
        assertThat(n.get("email_status")).isEqualTo("PENDING");
        assertThat(n.get("mandatory")).isEqualTo(false);

        jdbc.update("INSERT INTO notification_preferences (id, user_id, category, enable_in_app, enable_email) VALUES (?, ?, 'ORDER_AFFAIR', true, false)",
                UUID.randomUUID(), owner);
        done(vs.get(1), completed);
        submit(orderId, vs.get(1), sitterToken(), key(), "ok2").andExpect(status().isOk());
        assertThat(jdbc.queryForList("SELECT email_status FROM notifications WHERE user_id = ? AND type = 'CARE_LOG_SUBMITTED' ORDER BY created_at", String.class, owner))
                .containsExactly("PENDING", "SKIPPED_PREFERENCE");
    }

    @Test
    @DisplayName("PRD-022 AC-1.4（§7）交易失敗時 order_logs 與通知皆不存在、日誌仍 DRAFT（通知寫入失敗 → 500，整筆回滾）")
    void rollbackLeavesNoLog() throws Exception {
        UUID orderId = runningOrder("PRO", 1);
        UUID v = visits(orderId).get(0);
        draft(orderId, v, sitterToken(), "草稿").andExpect(status().isOk());
        done(v, OffsetDateTime.now(clock).minusMinutes(1));
        jdbc.execute("CREATE OR REPLACE FUNCTION fulfill_test_notify_fail() RETURNS trigger AS $$ BEGIN "
                + "RAISE EXCEPTION 'notify failure' USING ERRCODE = '23514'; END $$ LANGUAGE plpgsql");
        jdbc.execute("CREATE TRIGGER fulfill_test_notify_fail_trg BEFORE INSERT ON notifications FOR EACH ROW EXECUTE FUNCTION fulfill_test_notify_fail()");
        try {
            submit(orderId, v, sitterToken(), key(), "ok").andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
        } finally {
            jdbc.execute("DROP TRIGGER fulfill_test_notify_fail_trg ON notifications");
            jdbc.execute("DROP FUNCTION fulfill_test_notify_fail()");
        }
        assertThat(logCount(orderId, "CARE_LOG_SUBMITTED")).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM visit_service_reports WHERE visit_id = ?", String.class, v)).isEqualTo("DRAFT");
        JsonNode retry = body(submit(orderId, v, sitterToken(), key(), "ok").andExpect(status().isOk()));
        assertThat(retry.get("status").asString()).isEqualTo("SUBMITTED");
    }
}
