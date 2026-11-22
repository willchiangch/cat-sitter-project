package com.petsitter.application.service.notify;

import com.petsitter.application.exception.OrderException;
import com.petsitter.application.service.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * C11 Email 投遞（outbox，baseline §3.6；SD-ORDER §2.3.3、§8.4.1）。歸 SD-NOTIFY（設計未撰寫），B 階段過渡實作：
 * 候選 = {@code email_status = PENDING} 且到了下次嘗試時間；收件者為 {@code @e2e-journey.test} → SUPPRESSED_TEST 不呼叫 Resend；
 * 成功 SENT；4xx FAILED；其他重試，最多 5 次指數退避（2^n 分鐘）後 FAILED。I/O 不在交易內，每筆結果以單句 UPDATE 寫回。
 * 可選 {@code orderId}（只處理該測試訂單的通知）；自動觸發時排除測試帳號訂單與測試收件者。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationEmailDispatcher {

    public static final int MAX_ATTEMPTS = 5;
    private static final String TEST_SUFFIX = "@e2e-journey.test";

    private final JdbcTemplate jdbc;
    private final EmailService emailService;
    private final Clock clock;

    public record DispatchResult(String job, int processed, int skipped, int failed, OffsetDateTime startedAt,
                                 OffsetDateTime finishedAt) {
    }

    public DispatchResult dispatch(UUID orderId, Duration budget, int limit) {
        OffsetDateTime started = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
        if (orderId != null) {
            Integer test = jdbc.queryForObject("""
                    SELECT count(*) FROM orders o JOIN users u ON u.id IN (o.owner_id, o.sitter_id)
                    WHERE o.id = ? AND u.email LIKE '%@e2e-journey.test'""", Integer.class, orderId);
            if (test == null || test == 0) {
                throw OrderException.notFound();
            }
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT n.id, n.title, n.content, n.email_attempts, u.email
                FROM notifications n JOIN users u ON u.id = n.user_id
                WHERE n.email_status = 'PENDING' AND (n.email_next_attempt_at IS NULL OR n.email_next_attempt_at <= ?)
                  AND (CAST(? AS uuid) IS NULL OR n.related_order_id = CAST(? AS uuid))
                  AND (CAST(? AS uuid) IS NOT NULL OR (u.email NOT LIKE '%@e2e-journey.test' AND (n.related_order_id IS NULL OR NOT EXISTS (
                        SELECT 1 FROM orders o JOIN users x ON x.id IN (o.owner_id, o.sitter_id)
                        WHERE o.id = n.related_order_id AND x.email LIKE '%@e2e-journey.test'))))
                ORDER BY n.created_at LIMIT ?
                """, started, orderId, orderId, orderId, limit);
        long deadline = System.nanoTime() + budget.toNanos();
        int processed = 0;
        int failed = 0;
        for (Map<String, Object> r : rows) {
            if (System.nanoTime() > deadline) {
                break;
            }
            UUID id = (UUID) r.get("id");
            String email = (String) r.get("email");
            int attempts = ((Number) r.get("email_attempts")).intValue();
            if (email != null && email.endsWith(TEST_SUFFIX)) {
                mark(id, "SUPPRESSED_TEST", attempts, null, null);
                processed++;
                continue;
            }
            int status = emailService.deliver(email, (String) r.get("title"), "<p>" + r.get("content") + "</p>", id.toString());
            if (status == 0 || (status >= 200 && status < 300)) {
                mark(id, "SENT", attempts + 1, null, null);
                processed++;
            } else if (status >= 400 && status < 500) {
                mark(id, "FAILED", attempts + 1, null, "HTTP " + status);
                failed++;
            } else if (attempts + 1 >= MAX_ATTEMPTS) {
                mark(id, "FAILED", attempts + 1, null, status < 0 ? "network" : "HTTP " + status);
                failed++;
            } else {
                mark(id, "PENDING", attempts + 1, started.plusMinutes(1L << (attempts + 1)), status < 0 ? "network" : "HTTP " + status);
                failed++;
            }
        }
        return new DispatchResult("notifications/email-dispatch", processed, rows.size() - processed - failed, failed, started,
                OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC));
    }

    private void mark(UUID id, String status, int attempts, OffsetDateTime next, String error) {
        jdbc.update("UPDATE notifications SET email_status = ?, email_attempts = ?, email_next_attempt_at = ?, email_last_error = ? "
                + "WHERE id = ? AND email_status = 'PENDING'", status, attempts, next, error, id);
    }
}
