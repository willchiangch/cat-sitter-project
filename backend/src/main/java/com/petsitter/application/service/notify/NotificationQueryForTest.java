package com.petsitter.application.service.notify;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** SD-ORDER §10.4 inspect 用（SD-NOTIFY 過渡）：依關聯訂單列出通知與 Email 投遞狀態。 */
@Service
@RequiredArgsConstructor
public class NotificationQueryForTest {

    private final JdbcTemplate jdbc;

    public List<Map<String, Object>> findByRelatedOrderId(UUID orderId) {
        return jdbc.queryForList("""
                SELECT type, user_id AS "recipientUserId", role_target AS "recipientRole", mandatory,
                       email_status AS "emailStatus", created_at AS "createdAt"
                FROM notifications WHERE related_order_id = ? ORDER BY created_at, id""", orderId);
    }
}
