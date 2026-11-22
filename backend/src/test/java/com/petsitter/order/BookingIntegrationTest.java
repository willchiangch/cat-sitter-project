package com.petsitter.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** E1 送出預約（SD-ORDER §1.1、§10.3 BookingIntegrationTest）。 */
@DisplayName("E1 BookingIntegrationTest")
class BookingIntegrationTest extends OrderIntegrationTestBase {

    private Map<String, Object> twoGroups(LocalDate d1, LocalDate d2, LocalDate d3, int expected) {
        return Map.of("sitterId", sitter.toString(),
                "groups", List.of(
                        Map.of("planId", planA.toString(), "dates", List.of(d1.toString(), d2.toString()),
                                "dailyCount", 2, "petIds", List.of(petA.toString(), petB.toString())),
                        Map.of("planId", planB.toString(), "dates", List.of(d3.toString()),
                                "dailyCount", 1, "petIds", List.of(petB.toString()))),
                "expectedAmount", expected);
    }

    @Test
    @DisplayName("PRD-005 AC-3.1 建立訂單、群組、毛孩綁定、PENDING 行程與一筆 ORDER_CREATED；AC-2.1 後端重算 base_amount")
    void createsOrderWithItemsVisitsAndLog() throws Exception {
        LocalDate d = today().plusDays(5);
        JsonNode r = body(mvc.perform(postJson("/api/orders", ownerToken(), key(), twoGroups(d, d.plusDays(1), d.plusDays(3), 2800)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.baseAmount").value(2800))
                .andExpect(jsonPath("$.questionnaireStatus").value("NOT_REQUIRED")));
        UUID orderId = UUID.fromString(r.get("orderId").asString());
        assertThat(count("SELECT count(*) FROM order_items WHERE order_id = ?", orderId)).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM order_item_pets p JOIN order_items i ON i.id = p.order_item_id WHERE i.order_id = ?", orderId)).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM visits WHERE order_id = ? AND status = 'PENDING'", orderId)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT questionnaire_exempt_reason FROM orders WHERE id = ?", String.class, orderId))
                .isEqualTo("NO_ACTIVE_QUESTIONS");
        assertThat(logActions(orderId)).containsExactly("ORDER_CREATED");
        assertThat(jdbc.queryForObject("SELECT operator_user_id FROM order_logs WHERE order_id = ?", UUID.class, orderId)).isEqualTo(owner);
        assertThat(count("SELECT count(*) FROM notifications WHERE related_order_id = ? AND type = 'NEW_BOOKING_REQUEST' AND user_id = ?",
                orderId, sitter)).isEqualTo(1);
    }

    @Test
    @DisplayName("PRD-005 AC-3.2 評估期限：一般單 = 首日 00:00；緊急單 = 送單 + 2h")
    void reviewExpiresAt() throws Exception {
        LocalDate first = today().plusDays(4);
        UUID normal = book(first);
        OffsetDateTime expected = first.atStartOfDay(TAIPEI).toOffsetDateTime();
        assertThat(jdbc.queryForObject("SELECT review_expires_at FROM orders WHERE id = ?", OffsetDateTime.class, normal).toInstant())
                .isEqualTo(expected.toInstant());

        OffsetDateTime before = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
        UUID urgent = book(today());
        OffsetDateTime urgentAt = jdbc.queryForObject("SELECT review_expires_at FROM orders WHERE id = ?", OffsetDateTime.class, urgent);
        assertThat(Duration.between(before, urgentAt).toMinutes()).isBetween(119L, 121L);
    }

    @Test
    @DisplayName("PRD-005 AC-2.2 日期上下限：昨天、今天+366 → 422 INVALID_VISIT_DATE（payload 含日期）；今天+365 → 201")
    void dateBounds() throws Exception {
        LocalDate yesterday = today().minusDays(1);
        mvc.perform(postJson("/api/orders", ownerToken(), key(), bookingBody(sitter, List.of(yesterday), 1, 500, petA)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INVALID_VISIT_DATE"))
                .andExpect(jsonPath("$.payload.dates[0]").value(yesterday.toString()))
                .andExpect(jsonPath("$.payload.reason").value("DATE_OUT_OF_RANGE"));
        mvc.perform(postJson("/api/orders", ownerToken(), key(), bookingBody(sitter, List.of(today().plusDays(366)), 1, 500, petA)))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(postJson("/api/orders", ownerToken(), key(), bookingBody(sitter, List.of(today().plusDays(365)), 1, 500, petA)))
                .andExpect(status().isCreated());
        assertThat(count("SELECT count(*) FROM orders")).isEqualTo(1);
    }

    @Test
    @DisplayName("PRD-005 AC-2.3 方案可預約區間：PRO 區間外 422；降為 BASIC 後 201")
    void planRangeDependsOnTier() throws Exception {
        LocalDate d = today().plusDays(10);
        jdbc.update("UPDATE service_plans SET start_date = ?, end_date = ? WHERE id = ?", d.plusDays(5), d.plusDays(8), planA);
        mvc.perform(postJson("/api/orders", ownerToken(), key(), bookingBody(sitter, List.of(d), 1, 500, petA)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.payload.reason").value("PLAN_NOT_IN_RANGE"));
        tier(sitter, "BASIC");
        mvc.perform(postJson("/api/orders", ownerToken(), key(), bookingBody(sitter, List.of(d), 1, 500, petA)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("PRD-005 AC-2.5 毛孩：空 petIds 400（groupIndex）；他人毛孩 403；不存在 / 已刪除 404；他人毛孩 + 空群組 → 403 先於 400")
    void petBinding() throws Exception {
        LocalDate d = today().plusDays(3);
        Map<String, Object> emptySecond = Map.of("sitterId", sitter.toString(), "expectedAmount", 1000,
                "groups", List.of(Map.of("planId", planA.toString(), "dates", List.of(d.toString()), "dailyCount", 1, "petIds", List.of(petA.toString())),
                        Map.of("planId", planB.toString(), "dates", List.of(d.toString()), "dailyCount", 1, "petIds", List.of())));
        mvc.perform(postJson("/api/orders", ownerToken(), key(), emptySecond))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.payload.field").value("petIds"))
                .andExpect(jsonPath("$.payload.groupIndex").value(1));
        UUID othersPet = fx.pet(stranger, "別人的");
        mvc.perform(postJson("/api/orders", ownerToken(), key(), bookingBody(sitter, List.of(d), 1, 500, othersPet)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(postJson("/api/orders", ownerToken(), key(), bookingBody(sitter, List.of(d), 1, 500, UUID.randomUUID())))
                .andExpect(status().isNotFound());
        jdbc.update("UPDATE pets SET is_deleted = true WHERE id = ?", petB);
        mvc.perform(postJson("/api/orders", ownerToken(), key(), bookingBody(sitter, List.of(d), 1, 500, petB)))
                .andExpect(status().isNotFound());
        Map<String, Object> both = Map.of("sitterId", sitter.toString(), "expectedAmount", 1000,
                "groups", List.of(Map.of("planId", planA.toString(), "dates", List.of(d.toString()), "dailyCount", 1, "petIds", List.of(othersPet.toString())),
                        Map.of("planId", planB.toString(), "dates", List.of(d.toString()), "dailyCount", 1, "petIds", List.of())));
        mvc.perform(postJson("/api/orders", ownerToken(), key(), both)).andExpect(status().isForbidden());
        assertThat(count("SELECT count(*) FROM orders")).isZero();
        assertThat(count("SELECT count(*) FROM order_logs")).isZero();
    }

    @Test
    @DisplayName("PRD-005 AC-2.6 格式與數量上限：每日 0 / 4、群組 11、日期 61、同方案同日兩組 → 400；同日不同方案 201")
    void formatLimits() throws Exception {
        LocalDate d = today().plusDays(2);
        mvc.perform(postJson("/api/orders", ownerToken(), key(), bookingBody(sitter, List.of(d), 4, 2000, petA)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.payload.field").value("dailyCount"));
        mvc.perform(postJson("/api/orders", ownerToken(), key(), bookingBody(sitter, List.of(d), 0, 0, petA)))
                .andExpect(status().isBadRequest());
        List<Map<String, Object>> eleven = new java.util.ArrayList<>();
        for (int i = 0; i < 11; i++) {
            eleven.add(Map.of("planId", planA.toString(), "dates", List.of(d.plusDays(i).toString()), "dailyCount", 1, "petIds", List.of(petA.toString())));
        }
        mvc.perform(postJson("/api/orders", ownerToken(), key(), Map.of("sitterId", sitter.toString(), "groups", eleven, "expectedAmount", 5500)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.payload.field").value("groups"));
        List<LocalDate> sixtyOne = new java.util.ArrayList<>();
        for (int i = 0; i < 61; i++) {
            sixtyOne.add(d.plusDays(i));
        }
        mvc.perform(postJson("/api/orders", ownerToken(), key(), bookingBody(sitter, sixtyOne, 1, 61 * 500, petA)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.payload.field").value("dates"));
        Map<String, Object> dup = Map.of("sitterId", sitter.toString(), "expectedAmount", 1000,
                "groups", List.of(Map.of("planId", planA.toString(), "dates", List.of(d.toString()), "dailyCount", 1, "petIds", List.of(petA.toString())),
                        Map.of("planId", planA.toString(), "dates", List.of(d.toString()), "dailyCount", 1, "petIds", List.of(petB.toString()))));
        mvc.perform(postJson("/api/orders", ownerToken(), key(), dup)).andExpect(status().isBadRequest());
        Map<String, Object> sameDayDifferentPlan = Map.of("sitterId", sitter.toString(), "expectedAmount", 1300,
                "groups", List.of(Map.of("planId", planA.toString(), "dates", List.of(d.toString()), "dailyCount", 1, "petIds", List.of(petA.toString())),
                        Map.of("planId", planB.toString(), "dates", List.of(d.toString()), "dailyCount", 1, "petIds", List.of(petB.toString()))));
        mvc.perform(postJson("/api/orders", ownerToken(), key(), sameDayDifferentPlan)).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("PRD-005 AC-2.7 同日已有其他訂單不阻擋")
    void sameDayNotBlocked() throws Exception {
        LocalDate d = today().plusDays(3);
        book(d);
        book(d);
        assertThat(count("SELECT count(*) FROM orders WHERE status = 'PENDING'")).isEqualTo(2);
    }

    @Test
    @DisplayName("PRD-005 AC-2.8 方案不可預約：不存在 / 他人 / 下架 → 422 PLAN_NOT_BOOKABLE")
    void planNotBookable() throws Exception {
        LocalDate d = today().plusDays(3);
        UUID otherSitter = fx.user("o-" + UUID.randomUUID() + "@test.com", "SITTER");
        UUID othersPlan = fx.plan(otherSitter, "別人的方案", 500);
        jdbc.update("UPDATE service_plans SET is_active = false WHERE id = ?", planB);
        for (UUID plan : List.of(UUID.randomUUID(), othersPlan, planB)) {
            Map<String, Object> b = Map.of("sitterId", sitter.toString(), "expectedAmount", 500,
                    "groups", List.of(Map.of("planId", plan.toString(), "dates", List.of(d.toString()), "dailyCount", 1, "petIds", List.of(petA.toString()))));
            mvc.perform(postJson("/api/orders", ownerToken(), key(), b))
                    .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("PLAN_NOT_BOOKABLE"));
        }
    }

    @Test
    @DisplayName("PRD-005 AC-3.3 PRICE_CHANGED（payload baseAmount）；金額上限 999,999 → 400 field=baseAmount；剛好 999,999 → 201")
    void priceAndLimit() throws Exception {
        LocalDate d = today().plusDays(3);
        mvc.perform(postJson("/api/orders", ownerToken(), key(), bookingBody(sitter, List.of(d), 1, 1, petA)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PRICE_CHANGED"))
                .andExpect(jsonPath("$.payload.baseAmount").value(500));
        jdbc.update("UPDATE service_plans SET price = 333334 WHERE id = ?", planA);
        mvc.perform(postJson("/api/orders", ownerToken(), key(), bookingBody(sitter, List.of(d), 3, 1000002, petA)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.payload.field").value("baseAmount"))
                .andExpect(jsonPath("$.payload.max").value(999999));
        jdbc.update("UPDATE service_plans SET price = 333333 WHERE id = ?", planA);
        mvc.perform(postJson("/api/orders", ownerToken(), key(), bookingBody(sitter, List.of(d), 3, 999999, petA)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("PRD-005 AC-1.2 KYC 未通過 → 403 SITTER_NOT_BOOKABLE reason=NOT_VERIFIED")
    void kycNotVerified() throws Exception {
        jdbc.update("UPDATE profiles SET kyc_status = 'PENDING_REVIEW' WHERE user_id = ?", sitter);
        mvc.perform(postJson("/api/orders", ownerToken(), key(), bookingBody(sitter, List.of(today().plusDays(2)), 1, 500, petA)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SITTER_NOT_BOOKABLE"))
                .andExpect(jsonPath("$.payload.reason").value("NOT_VERIFIED"));
        assertThat(count("SELECT count(*) FROM orders")).isZero();
    }

    @Test
    @DisplayName("PRD-005 AC-1.3 / AC-1.5 隱藏、PRO 全域黑名單、ULTIMATE 全域白名單外 → 同一 UNAVAILABLE 回應；降級後 201")
    void unavailableSameResponse() throws Exception {
        LocalDate d = today().plusDays(2);
        jdbc.update("INSERT INTO gatekeeper_rules (id, sitter_id, rule_type, scope_type, target_user_id) VALUES (?, ?, 'BLACK', 'GLOBAL', ?)",
                UUID.randomUUID(), sitter, owner);
        JsonNode blacklisted = body(mvc.perform(postJson("/api/orders", ownerToken(), key(), bookingBody(sitter, List.of(d), 1, 500, petA)))
                .andExpect(status().isForbidden()));
        jdbc.update("DELETE FROM gatekeeper_rules");
        jdbc.update("UPDATE profiles SET is_visible = false WHERE user_id = ?", sitter);
        JsonNode hidden = body(mvc.perform(postJson("/api/orders", ownerToken(), key(), bookingBody(sitter, List.of(d), 1, 500, petA)))
                .andExpect(status().isForbidden()));
        assertThat(hidden.get("code")).isEqualTo(blacklisted.get("code"));
        assertThat(hidden.get("payload")).isEqualTo(blacklisted.get("payload"));
        assertThat(blacklisted.get("payload").get("reason").asString()).isEqualTo("UNAVAILABLE");

        jdbc.update("UPDATE profiles SET is_visible = true WHERE user_id = ?", sitter);
        tier(sitter, "ULTIMATE");
        jdbc.update("INSERT INTO gatekeeper_rules (id, sitter_id, rule_type, scope_type, target_user_id) VALUES (?, ?, 'WHITE', 'GLOBAL', ?)",
                UUID.randomUUID(), sitter, stranger);
        JsonNode white = body(mvc.perform(postJson("/api/orders", ownerToken(), key(), bookingBody(sitter, List.of(d), 1, 500, petA)))
                .andExpect(status().isForbidden()));
        assertThat(white.get("payload")).isEqualTo(blacklisted.get("payload"));
        tier(sitter, "PRO");
        mvc.perform(postJson("/api/orders", ownerToken(), key(), bookingBody(sitter, List.of(d), 1, 500, petA)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("PRD-005 AC-1.4 方案黑名單（PRO）→ 422 PLAN_NOT_BOOKABLE；降為 BASIC 後名單不生效")
    void planBlacklist() throws Exception {
        LocalDate d = today().plusDays(2);
        jdbc.update("INSERT INTO gatekeeper_rules (id, sitter_id, rule_type, scope_type, plan_id, target_user_id) VALUES (?, ?, 'BLACK', 'PLAN', ?, ?)",
                UUID.randomUUID(), sitter, planA, owner);
        mvc.perform(postJson("/api/orders", ownerToken(), key(), bookingBody(sitter, List.of(d), 1, 500, petA)))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("PLAN_NOT_BOOKABLE"));
        tier(sitter, "BASIC");
        mvc.perform(postJson("/api/orders", ownerToken(), key(), bookingBody(sitter, List.of(d), 1, 500, petA)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("PRD-005 AC-1.6 訂閱到期隔天 00:00（Asia/Taipei）起 403 SUBSCRIPTION_LOCKED；停權同碼（非 SITTER_NOT_BOOKABLE）")
    void subscriptionLocked() throws Exception {
        LocalDate d = today().plusDays(2);
        jdbc.update("UPDATE subscriptions SET expired_at = ? WHERE sitter_id = ?",
                OffsetDateTime.now(clock).minusDays(2), sitter);
        mvc.perform(postJson("/api/orders", ownerToken(), key(), bookingBody(sitter, List.of(d), 1, 500, petA)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SUBSCRIPTION_LOCKED"));
        jdbc.update("UPDATE subscriptions SET expired_at = ? WHERE sitter_id = ?", OffsetDateTime.now(clock), sitter);
        mvc.perform(postJson("/api/orders", ownerToken(), key(), bookingBody(sitter, List.of(d), 1, 500, petA)))
                .andExpect(status().isCreated());
        jdbc.update("UPDATE subscriptions SET expired_at = NULL WHERE sitter_id = ?", sitter);
        jdbc.update("UPDATE profiles SET kyc_status = 'SUSPENDED' WHERE user_id = ?", sitter);
        mvc.perform(postJson("/api/orders", ownerToken(), key(), bookingBody(sitter, List.of(d), 1, 500, petA)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SUBSCRIPTION_LOCKED"));
    }

    @Test
    @DisplayName("PRD-005 AC-1.7 每月上限：FREE 當月 3 張 CONFIRMED（含之後取消）→ 422；PENDING 不計")
    void monthlyQuota() throws Exception {
        tier(sitter, "FREE");
        LocalDate d = today().plusDays(2);
        for (int i = 0; i < 5; i++) {
            book(d);
        }
        for (int i = 0; i < 3; i++) {
            UUID o = fx.order(owner, sitter, i == 2 ? "CANCELLED" : "CONFIRMED");
            jdbc.update("UPDATE orders SET confirmed_at = ? WHERE id = ?", OffsetDateTime.now(clock), o);
        }
        mvc.perform(postJson("/api/orders", ownerToken(), key(), bookingBody(sitter, List.of(d), 1, 500, petA)))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("MONTHLY_QUOTA_REACHED"));
        tier(sitter, "BASIC");
        mvc.perform(postJson("/api/orders", ownerToken(), key(), bookingBody(sitter, List.of(d), 1, 500, petA)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("PRD-005 AC-1.8 預約自己 → 422 SELF_BOOKING_NOT_ALLOWED（即使已鎖定且隱藏）；SITTER / ADMIN token → 403 ROLE_MISMATCH")
    void selfBookingAndRole() throws Exception {
        UUID dual = fx.user("dual-" + UUID.randomUUID() + "@test.com", "SITTER");
        fx.profile(dual, "SITTER", "VERIFIED");
        jdbc.update("UPDATE profiles SET is_visible = false WHERE user_id = ?", dual);
        UUID ownPet = fx.pet(dual, "自己的貓");
        mvc.perform(postJson("/api/orders", bearer(dual, "OWNER"), key(), bookingBody(dual, List.of(today().plusDays(2)), 1, 500, ownPet)))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("SELF_BOOKING_NOT_ALLOWED"));
        mvc.perform(postJson("/api/orders", bearer(owner, "SITTER"), key(), bookingBody(sitter, List.of(today().plusDays(2)), 1, 500, petA)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ROLE_MISMATCH"));
        mvc.perform(postJson("/api/orders", bearer(admin, "ADMIN"), key(), bookingBody(sitter, List.of(today().plusDays(2)), 1, 500, petA)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ROLE_MISMATCH"));
        assertThat(count("SELECT count(*) FROM orders")).isZero();
    }

    @Test
    @DisplayName("PRD-005 AC-1.1 未帶 token 送單 → 401 UNAUTHENTICATED")
    void anonymous401() throws Exception {
        mvc.perform(postJson("/api/orders", null, key(), bookingBody(sitter, List.of(today().plusDays(2)), 1, 500, petA)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }
}
