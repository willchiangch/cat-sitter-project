package com.petsitter.application.service.fulfill;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * SD-FULFILL §2.3.3 / §10.2：掛進 SD-ORDER {@code GET /api/internal/test-data/orders/{orderId}/inspect} 的 {@code fulfill}
 * 區塊——行程 / 日誌 / 媒體 / 檢核項 / 上傳槽位的唯讀快照。{@code SUPPORTS}、固定 5 次查詢、不含簽名網址；
 * 只由 test-data 端點使用（FulfillAggregateArchTest 限制呼叫者）。
 */
@Service
@RequiredArgsConstructor
public class FulfillTestInspectionQuery {

    private final JdbcTemplate jdbc;

    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public Map<String, Object> inspect(UUID orderId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("visits", jdbc.queryForList("""
                SELECT id AS "visitId", service_date AS "serviceDate", plan_id AS "planId", status,
                       completion_source AS "completionSource", started_at AS "startedAt", completed_at AS "completedAt",
                       closed_at AS "closedAt", cancelled_at AS "cancelledAt", custom_name AS "customName"
                FROM visits WHERE order_id = ? ORDER BY service_date, sequence_in_day, id""", orderId));
        out.put("careLogs", jdbc.queryForList("""
                SELECT r.visit_id AS "visitId", r.id AS "careLogId", r.status, r.submitted_at AS "submittedAt",
                       r.created_by AS "createdBy", r.updated_by AS "updatedBy"
                FROM visit_service_reports r JOIN visits v ON v.id = r.visit_id WHERE v.order_id = ?
                ORDER BY v.service_date, v.sequence_in_day""", orderId));
        out.put("media", jdbc.queryForList("""
                SELECT m.id AS "mediaId", r.visit_id AS "visitId", m.media_type AS "mediaType", m.size_bytes AS "sizeBytes",
                       m.duration_seconds AS "durationSeconds", m.object_key AS "objectKey", m.is_purged AS "purged"
                FROM service_report_media m JOIN visit_service_reports r ON r.id = m.report_id JOIN visits v ON v.id = r.visit_id
                WHERE v.order_id = ? ORDER BY m.uploaded_at, m.id""", orderId));
        out.put("checklistItems", jdbc.queryForList("""
                SELECT c.id AS "itemId", c.visit_id AS "visitId", c.title, c.sort_order AS "sortOrder",
                       c.is_completed AS "isCompleted", c.checked_at AS "checkedAt", c.created_source AS "createdSource",
                       c.created_by AS "createdBy", c.updated_by AS "updatedBy", c.updated_at AS "updatedAt"
                FROM visit_checklist_items c JOIN visits v ON v.id = c.visit_id WHERE v.order_id = ?
                ORDER BY v.service_date, v.sequence_in_day, c.sort_order""", orderId));
        out.put("uploads", jdbc.queryForList("""
                SELECT id AS "uploadId", visit_id AS "visitId", status, received_at AS "receivedAt", expires_at AS "expiresAt",
                       failure_code AS "failureCode", object_deleted_at AS "objectDeletedAt"
                FROM care_log_media_uploads WHERE order_id = ? ORDER BY received_at, id""", orderId));
        return out;
    }
}
