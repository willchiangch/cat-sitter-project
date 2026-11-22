package com.petsitter.application.service.fulfill;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * SD-FULFILL §2.3.3 {@code inspect(orderId)} 的 B 階段最小實作：行程 / 日誌 / 媒體 / 檢核項 / 上傳槽位的唯讀快照
 * （固定 5 次查詢、不含簽名網址）。TODO(C 階段 F-M2)：欄位依 SD-FULFILL §10.2 定稿。
 */
@Service
@RequiredArgsConstructor
public class FulfillTestInspectionQuery {

    private final JdbcTemplate jdbc;

    public Map<String, Object> inspect(UUID orderId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("visits", jdbc.queryForList("""
                SELECT id AS "visitId", service_date AS "serviceDate", plan_id AS "planId", status, started_at AS "startedAt",
                       completed_at AS "completedAt", closed_at AS "closedAt", cancelled_at AS "cancelledAt"
                FROM visits WHERE order_id = ? ORDER BY service_date, sequence_in_day""", orderId));
        out.put("careLogs", jdbc.queryForList("""
                SELECT r.id AS "careLogId", r.visit_id AS "visitId", r.status, r.submitted_at AS "submittedAt"
                FROM visit_service_reports r JOIN visits v ON v.id = r.visit_id WHERE v.order_id = ?""", orderId));
        out.put("media", jdbc.queryForList("""
                SELECT m.id AS "mediaId", m.report_id AS "careLogId", m.media_type AS "mediaType", m.is_purged AS "purged"
                FROM service_report_media m JOIN visit_service_reports r ON r.id = m.report_id JOIN visits v ON v.id = r.visit_id
                WHERE v.order_id = ?""", orderId));
        out.put("checklistItems", jdbc.queryForList("""
                SELECT c.id AS "itemId", c.visit_id AS "visitId", c.title, c.is_completed AS "completed"
                FROM visit_checklist_items c JOIN visits v ON v.id = c.visit_id WHERE v.order_id = ?""", orderId));
        out.put("uploads", jdbc.queryForList("""
                SELECT id AS "uploadId", visit_id AS "visitId", status FROM care_log_media_uploads WHERE order_id = ?""", orderId));
        return out;
    }

    List<Map<String, Object>> visits(UUID orderId) {
        return jdbc.queryForList("SELECT id FROM visits WHERE order_id = ?", orderId);
    }
}
