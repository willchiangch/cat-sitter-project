package testsupport.order;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * 以 JDBC 直接建立符合新 schema（V20261122_01）CHECK 約束的訂單測試資料，不經 Hibernate 一級快取。
 * 只給測試使用；正式程式一律經 SD-ORDER Service。
 */
public final class OrderFixtures {

    private final JdbcTemplate jdbc;

    public OrderFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public static Timestamp ts(OffsetDateTime t) {
        return t == null ? null : Timestamp.from(t.toInstant());
    }

    public UUID user(String email, String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, password_hash, full_name, role) VALUES (?, ?, 'x', ?, ?)",
                id, email, email.substring(0, email.indexOf('@')), role);
        return id;
    }

    public UUID profile(UUID userId, String type, String kycStatus) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO profiles (id, user_id, type, kyc_status) VALUES (?, ?, ?, ?)", id, userId, type, kycStatus);
        return id;
    }

    public UUID plan(UUID sitterId, String name, int price) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO service_plans (id, sitter_id, name, price) VALUES (?, ?, ?, ?)", id, sitterId, name, price);
        return id;
    }

    public UUID pet(UUID ownerId, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO pets (id, owner_id, name, species) VALUES (?, ?, ?, 'CAT')", id, ownerId, name);
        return id;
    }

    /**
     * 建立指定主狀態的訂單（欄位組合滿足 ck_orders_*）。報價後狀態帶 total = base、加減 0；
     * CONFIRMED 以後帶 confirmed_at；終態帶對應時間與操作者。
     */
    public UUID order(UUID ownerId, UUID sitterId, String status) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        boolean quoted = !"PENDING".equals(status) && !"CANCELLED".equals(status);
        boolean confirmed = List.of("CONFIRMED", "IN_PROGRESS", "DISPUTED", "COMPLETED").contains(status);
        jdbc.update("""
                INSERT INTO orders (id, owner_id, sitter_id, status, base_amount, surcharge_amount, discount_amount,
                    total_amount, quoted_at, confirmed_at, review_expires_at, first_service_date, questionnaire_status,
                    questionnaire_exempt_reason, cancelled_by, cancelled_at, cancel_reason_code, completed_by,
                    completed_at, disputed_at)
                VALUES (?, ?, ?, ?, 1000, ?, ?, ?, ?, ?, ?, ?, 'NOT_REQUIRED', 'NO_ACTIVE_QUESTIONS', ?, ?, ?, ?, ?, ?)
                """,
                id, ownerId, sitterId, status,
                quoted ? 0 : null, quoted ? 0 : null, quoted ? 1000 : null, quoted ? ts(now) : null,
                confirmed ? ts(now) : null, ts(now.plusDays(1)), Date.valueOf(LocalDate.now().plusDays(2)),
                "CANCELLED".equals(status) ? "SYSTEM" : null, "CANCELLED".equals(status) ? ts(now) : null,
                "CANCELLED".equals(status) ? "REVIEW_EXPIRED" : null,
                "COMPLETED".equals(status) ? "SYSTEM" : null, "COMPLETED".equals(status) ? ts(now) : null,
                "DISPUTED".equals(status) ? ts(now) : null);
        return id;
    }

    public UUID bookingItem(UUID orderId, UUID planId, int groupIndex, LocalDate... dates) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO order_items (id, order_id, source, group_index, plan_id, service_dates, daily_count, booked_unit_price)
                VALUES (?, ?, 'BOOKING', ?, ?, ?::date[], 1, 500)
                """, id, orderId, groupIndex, planId, "{" + String.join(",", java.util.Arrays.stream(dates).map(LocalDate::toString).toList()) + "}");
        return id;
    }

    public void bindPet(UUID orderItemId, UUID petId) {
        jdbc.update("INSERT INTO order_item_pets (order_item_id, pet_id) VALUES (?, ?)", orderItemId, petId);
    }

    public UUID visit(UUID orderId, UUID itemId, UUID ownerId, UUID sitterId, UUID planId, LocalDate date, String status) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        boolean started = "IN_PROGRESS".equals(status) || "DONE".equals(status);
        jdbc.update("""
                INSERT INTO visits (id, order_id, order_item_id, owner_id, sitter_id, status, plan_id, booking_plan_title,
                    service_date, sequence_in_day, slots_in_day, started_at, completed_at, completion_source, cancelled_at, closed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, '方案', ?, 1, 1, ?, ?, ?, ?, ?)
                """, id, orderId, itemId, ownerId, sitterId, status, planId, Date.valueOf(date),
                started ? ts(now) : null, "DONE".equals(status) ? ts(now) : null, "DONE".equals(status) ? "SITTER" : null,
                "CANCELLED".equals(status) ? ts(now) : null, "CLOSED_BY_SYSTEM".equals(status) ? ts(now) : null);
        return id;
    }

    public void snapshot(UUID orderId, String tier, int maxPhotos, int maxVideos, int retentionDays) {
        jdbc.update("INSERT INTO order_snapshots (id, order_id, plan_tier, max_photos, max_videos, media_retention_days) "
                + "VALUES (?, ?, ?, ?, ?, ?)", UUID.randomUUID(), orderId, tier, maxPhotos, maxVideos, retentionDays);
    }

    public UUID submittedReport(UUID visitId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO visit_service_reports (id, visit_id, status, content, submitted_at) VALUES (?, ?, 'SUBMITTED', 'ok', now())",
                id, visitId);
        return id;
    }

    /** 已完成上傳的日誌媒體（含對應槽位列）。 */
    public UUID media(UUID orderId, UUID visitId, UUID sitterId, UUID reportId, String objectKey) {
        UUID upload = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO care_log_media_uploads (id, visit_id, order_id, sitter_id, media_type, content_type, declared_size,
                    declared_sha256, object_key, status, received_at, expires_at, completed_at)
                VALUES (?, ?, ?, ?, 'IMAGE', 'image/jpeg', 10, ?, ?, 'COMPLETED', now(), now() + interval '30 minutes', now())
                """, upload, visitId, orderId, sitterId, "0".repeat(64), objectKey);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO service_report_media (id, report_id, object_key, media_type, upload_id, object_generation,
                    size_bytes, uploaded_by, uploaded_at)
                VALUES (?, ?, ?, 'IMAGE', ?, 1, 10, ?, now())
                """, id, reportId, objectKey, upload, sitterId);
        return id;
    }
}
