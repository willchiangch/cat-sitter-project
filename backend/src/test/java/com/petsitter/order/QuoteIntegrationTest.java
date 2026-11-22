package com.petsitter.order;

import com.petsitter.application.service.order.PayoutSnapshotCipher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** E6 O2 報價（PRD-006 AC-2.x / AC-3.x、PRD-007 AC-3.1；SD-ORDER §10.3 QuoteIntegrationTest）。 */
@DisplayName("E6 QuoteIntegrationTest")
class QuoteIntegrationTest extends OrderIntegrationTestBase {

    @Autowired
    private PayoutSnapshotCipher cipher;

    private org.springframework.test.web.servlet.ResultActions quoteWith(UUID orderId, Map<String, Object> body) throws Exception {
        return mvc.perform(postJson("/api/orders/{id}/quote", sitterToken(), key(), body, orderId));
    }

    @Test
    @DisplayName("PRD-006 AC-2.1 / AC-2.3 / AC-2.4 原價接單：金額欄、quoted_at、快照、INITIAL payment 期限三欄、ORDER_QUOTED 與飼主通知；PRD-007 AC-3.1")
    void quoteOriginal() throws Exception {
        LocalDate first = today().plusDays(10);
        UUID orderId = book(first, first.plusDays(1));
        JsonNode r = body(quoteWith(orderId, Map.of("expectedAmount", 1000))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.totalAmount").value(1000)));
        Map<String, Object> o = jdbc.queryForMap("SELECT * FROM orders WHERE id = ?", orderId);
        assertThat(o.get("quoted_at")).isNotNull();
        assertThat(o.get("surcharge_amount")).isEqualTo(0);
        assertThat(count("SELECT count(*) FROM order_plan_snapshots WHERE order_id = ? AND unit_price = 500 AND plan_title = '到府餵食'", orderId)).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT plan_tier, max_photos, max_videos, media_retention_days FROM order_snapshots WHERE order_id = ?", orderId))
                .containsEntry("plan_tier", "PRO").containsEntry("max_photos", 10).containsEntry("max_videos", 2).containsEntry("media_retention_days", 90);
        Map<String, Object> p = jdbc.queryForMap("SELECT * FROM payments WHERE order_id = ?", orderId);
        OffsetDateTime firstDay = first.atStartOfDay(TAIPEI).toOffsetDateTime();
        assertThat(((java.sql.Timestamp) p.get("due_at")).toInstant()).isEqualTo(firstDay.minusHours(24).toInstant());
        assertThat(((java.sql.Timestamp) p.get("escalate_at")).toInstant()).isEqualTo(firstDay.plusHours(2).toInstant());
        assertThat(((java.sql.Timestamp) p.get("remind_at")).toInstant()).isEqualTo(firstDay.minusHours(48).toInstant());
        assertThat(p.get("status")).isEqualTo("UNPAID");
        assertThat(p.get("amount")).isEqualTo(1000);
        // 加密快照非明文且可解回四欄
        String cipherText = (String) p.get("payee_account_snapshot");
        assertThat(cipherText).doesNotContain("1234567890123").doesNotContain("王小明");
        assertThat(cipher.decrypt(cipherText, ((Number) p.get("payee_account_key_version")).shortValue()).accountNumber())
                .isEqualTo("1234567890123");
        assertThat(logActions(orderId)).containsExactly("ORDER_CREATED", "ORDER_QUOTED");
        assertThat(count("SELECT count(*) FROM notifications WHERE related_order_id = ? AND type = 'QUOTE_SENT' AND mandatory = false", orderId)).isEqualTo(1);
        assertThat(r.get("payment").get("dueAt")).isNotNull();
    }

    @Test
    @DisplayName("PRD-006 AC-2.5 緊急單：due_at = 報價 + 2h、remind_at = null；通知併付款提醒且不可關閉")
    void urgent() throws Exception {
        UUID orderId = book(today().plusDays(1));
        OffsetDateTime before = OffsetDateTime.now(clock);
        quoteWith(orderId, Map.of("expectedAmount", 500)).andExpect(status().isOk());
        Map<String, Object> p = jdbc.queryForMap("SELECT due_at, remind_at FROM payments WHERE order_id = ?", orderId);
        assertThat(p.get("remind_at")).isNull();
        assertThat(Duration.between(before.toInstant(), ((java.sql.Timestamp) p.get("due_at")).toInstant()).toMinutes()).isBetween(119L, 121L);
        assertThat(count("SELECT count(*) FROM notifications WHERE related_order_id = ? AND type = 'QUOTE_SENT_WITH_PAYMENT_REMINDER' AND mandatory = true", orderId)).isEqualTo(1);
    }

    @Test
    @DisplayName("PRD-006 AC-2.2 PRO 調價：加價 / 折扣與原因寫入；AC-3.2 FREE / BASIC 帶調整 → 403 PLAN_REQUIRED（原價仍可）")
    void adjustmentByTier() throws Exception {
        UUID orderId = book(today().plusDays(5));
        tier(sitter, "BASIC");
        quoteWith(orderId, Map.of("expectedAmount", 500, "surchargeAmount", 100, "pricingNotes", "假日"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PLAN_REQUIRED"));
        tier(sitter, "PRO");
        quoteWith(orderId, Map.of("expectedAmount", 500, "surchargeAmount", 200, "discountAmount", 50, "pricingNotes", "　假日加價 "))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalAmount").value(650));
        assertThat(jdbc.queryForObject("SELECT pricing_notes FROM orders WHERE id = ?", String.class, orderId)).isEqualTo("假日加價");
    }

    @Test
    @DisplayName("PRD-006 AC-3.3 金額驗證：100.5 / 100.0 → 400；調整 > 0 缺原因 → 400；總額 < 1 → 422 INVALID_QUOTE_AMOUNT；> 999,999 → 400 totalAmount")
    void amountValidation() throws Exception {
        UUID orderId = book(today().plusDays(5));
        mvc.perform(postJson("/api/orders/{id}/quote", sitterToken(), key(), "{\"expectedAmount\":500,\"surchargeAmount\":100.5,\"pricingNotes\":\"x\"}", orderId))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.payload.field").value("surchargeAmount"));
        mvc.perform(postJson("/api/orders/{id}/quote", sitterToken(), key(), "{\"expectedAmount\":500.0}", orderId))
                .andExpect(status().isBadRequest());
        quoteWith(orderId, Map.of("expectedAmount", 500, "discountAmount", 100)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.payload.field").value("pricingNotes"));
        quoteWith(orderId, Map.of("expectedAmount", 500, "discountAmount", 500, "pricingNotes", "免費"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("INVALID_QUOTE_AMOUNT"));
        quoteWith(orderId, Map.of("expectedAmount", 500, "surchargeAmount", 999999, "pricingNotes", "x"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.payload.field").value("totalAmount"));
        assertThat(orderStatus(orderId)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("PRD-006 AC-3.4 評估期限已過 → 409 DEADLINE_PASSED（REVIEW_EXPIRES_AT）；首日已過 → FIRST_VISIT_DATE_PASSED（排在 DEADLINE 之後）")
    void deadlines() throws Exception {
        UUID orderId = book(today().plusDays(2));
        advance(Duration.ofDays(3));
        quoteWith(orderId, Map.of("expectedAmount", 500)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEADLINE_PASSED")).andExpect(jsonPath("$.payload.deadline").value("REVIEW_EXPIRES_AT"));
        clock.reset();
        UUID other = book(today().plusDays(2));
        jdbc.update("UPDATE orders SET first_service_date = ?, review_expires_at = ? WHERE id = ?",
                today().minusDays(1), OffsetDateTime.now(clock).plusDays(1), other);
        quoteWith(other, Map.of("expectedAmount", 500)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FIRST_VISIT_DATE_PASSED"));
    }

    @Test
    @DisplayName("PRD-006 AC-3.5 月接單額度已滿 → 422 MONTHLY_QUOTA_REACHED；AC-3.8 未設收款帳號 → 422 PAYOUT_ACCOUNT_REQUIRED")
    void quotaAndPayout() throws Exception {
        UUID orderId = book(today().plusDays(5));
        jdbc.update("UPDATE profiles SET bank_account_info = NULL WHERE user_id = ?", sitter);
        quoteWith(orderId, Map.of("expectedAmount", 500)).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PAYOUT_ACCOUNT_REQUIRED"));
        tier(sitter, "FREE");
        for (int i = 0; i < 3; i++) {
            UUID o = fx.order(owner, sitter, "CONFIRMED");
            jdbc.update("UPDATE orders SET confirmed_at = ? WHERE id = ?", OffsetDateTime.now(clock), o);
        }
        quoteWith(orderId, Map.of("expectedAmount", 500)).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("MONTHLY_QUOTA_REACHED"));
    }

    @Test
    @DisplayName("PRD-006 AC-3.6 訂閱鎖定 → 報價 403 SUBSCRIPTION_LOCKED，評估頁仍 200")
    void locked() throws Exception {
        UUID orderId = book(today().plusDays(5));
        jdbc.update("UPDATE subscriptions SET expired_at = ? WHERE sitter_id = ?", OffsetDateTime.now(clock).minusDays(3), sitter);
        quoteWith(orderId, Map.of("expectedAmount", 500)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SUBSCRIPTION_LOCKED"));
        mvc.perform(getAs("/api/orders/{id}/evaluation", sitterToken(), orderId)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("PRD-006 AC-3.7 單價改過 → 409 PRICE_CHANGED（baseAmount / totalAmount 為重算值）；停用方案仍以其單價重算")
    void priceChanged() throws Exception {
        UUID orderId = book(today().plusDays(5));
        jdbc.update("UPDATE service_plans SET price = 700, is_active = false WHERE id = ?", planA);
        quoteWith(orderId, Map.of("expectedAmount", 500)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRICE_CHANGED"))
                .andExpect(jsonPath("$.payload.baseAmount").value(700)).andExpect(jsonPath("$.payload.totalAmount").value(700));
        quoteWith(orderId, Map.of("expectedAmount", 700)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT base_amount FROM orders WHERE id = ?", Integer.class, orderId)).isEqualTo(700);
    }

    @Test
    @DisplayName("PRD-006 AC-2.7 報價後不可再報價 → 409 ORDER_STATE_CONFLICT（currentStatus）")
    void quotedCannotRequote() throws Exception {
        UUID orderId = book(today().plusDays(5));
        quote(orderId);
        quoteWith(orderId, Map.of("expectedAmount", 500)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_STATE_CONFLICT"))
                .andExpect(jsonPath("$.payload.currentStatus").value("PENDING_PAYMENT"));
    }

    @Test
    @DisplayName("PRD-006 AC-3.1 報價越權：他人保母 403、OWNER 403 ROLE_MISMATCH、不存在 404")
    void access() throws Exception {
        UUID orderId = book(today().plusDays(5));
        UUID other = fx.user("os-" + UUID.randomUUID() + "@test.com", "SITTER");
        mvc.perform(postJson("/api/orders/{id}/quote", bearer(other, "SITTER"), key(), Map.of("expectedAmount", 500), orderId))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(postJson("/api/orders/{id}/quote", ownerToken(), key(), Map.of("expectedAmount", 500), orderId))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ROLE_MISMATCH"));
        mvc.perform(postJson("/api/orders/{id}/quote", sitterToken(), key(), Map.of("expectedAmount", 500), UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }
}
