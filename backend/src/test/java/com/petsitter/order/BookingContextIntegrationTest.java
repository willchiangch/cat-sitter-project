package com.petsitter.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** E31 booking-context（SD-ORDER §6.3 E31、§10.3 BookingContextIntegrationTest）。 */
@DisplayName("E31 BookingContextIntegrationTest")
class BookingContextIntegrationTest extends OrderIntegrationTestBase {

    private JsonNode ctx(String token) throws Exception {
        return body(mvc.perform(getAs("/api/sitters/{id}/booking-context", token, sitter)).andExpect(status().isOk()));
    }

    @Test
    @DisplayName("PRD-005 AC-1.1 匿名可瀏覽（BOOKABLE、方案清單、serverTime），不回問卷；OWNER 回問卷")
    void anonymousAndOwner() throws Exception {
        JsonNode anon = ctx(null);
        assertThat(anon.get("bookability").asString()).isEqualTo("BOOKABLE");
        assertThat(anon.get("plans").size()).isEqualTo(2);
        assertThat(anon.get("serverTime")).isNotNull();
        assertThat(anon.has("questionnaire")).isFalse();
        JsonNode asOwner = ctx(ownerToken());
        assertThat(asOwner.get("questionnaire").get("required").asBoolean()).isFalse();
        assertThat(asOwner.get("questionnaire").get("exemptReason").asString()).isEqualTo("NO_ACTIVE_QUESTIONS");
        mvc.perform(getAs("/api/sitters/{id}/booking-context", null, UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PRD-005 AC-1.2 KYC 未通過 → NOT_VERIFIED 且不回方案")
    void notVerified() throws Exception {
        jdbc.update("UPDATE profiles SET kyc_status = 'UNVERIFIED' WHERE user_id = ?", sitter);
        JsonNode r = ctx(ownerToken());
        assertThat(r.get("bookability").asString()).isEqualTo("NOT_VERIFIED");
        assertThat(r.get("plans").size()).isZero();
    }

    @Test
    @DisplayName("PRD-005 AC-1.3 / AC-1.5 隱藏、全域黑名單、全域白名單外 → UNAVAILABLE（三者回應相同、不回方案）")
    void unavailable() throws Exception {
        jdbc.update("UPDATE profiles SET is_visible = false WHERE user_id = ?", sitter);
        JsonNode hidden = ctx(ownerToken());
        jdbc.update("UPDATE profiles SET is_visible = true WHERE user_id = ?", sitter);
        jdbc.update("INSERT INTO gatekeeper_rules (id, sitter_id, rule_type, scope_type, target_user_id) VALUES (?, ?, 'BLACK', 'GLOBAL', ?)",
                UUID.randomUUID(), sitter, owner);
        JsonNode black = ctx(ownerToken());
        jdbc.update("DELETE FROM gatekeeper_rules");
        tier(sitter, "ULTIMATE");
        jdbc.update("INSERT INTO gatekeeper_rules (id, sitter_id, rule_type, scope_type, target_user_id) VALUES (?, ?, 'WHITE', 'GLOBAL', ?)",
                UUID.randomUUID(), sitter, stranger);
        JsonNode white = ctx(null);
        for (JsonNode n : new JsonNode[]{hidden, black, white}) {
            assertThat(n.get("bookability").asString()).isEqualTo("UNAVAILABLE");
            assertThat(n.get("plans").size()).isZero();
        }
        assertThat(ctx(bearer(stranger, "OWNER")).get("bookability").asString()).isEqualTo("BOOKABLE");
    }

    @Test
    @DisplayName("PRD-005 AC-1.4 方案黑 / 白名單依等級：ULTIMATE 白名單方案只給名單內、黑名單方案不給名單內；BASIC 全部可見")
    void planGatekeeperByTier() throws Exception {
        tier(sitter, "ULTIMATE");
        jdbc.update("INSERT INTO gatekeeper_rules (id, sitter_id, rule_type, scope_type, plan_id, target_user_id) VALUES (?, ?, 'WHITE', 'PLAN', ?, ?)",
                UUID.randomUUID(), sitter, planA, owner);
        jdbc.update("INSERT INTO gatekeeper_rules (id, sitter_id, rule_type, scope_type, plan_id, target_user_id) VALUES (?, ?, 'BLACK', 'PLAN', ?, ?)",
                UUID.randomUUID(), sitter, planB, stranger);
        assertThat(ctx(ownerToken()).get("plans").size()).isEqualTo(2);
        assertThat(ctx(bearer(stranger, "OWNER")).get("plans").size()).isZero();
        assertThat(ctx(null).get("plans").size()).isEqualTo(1);
        tier(sitter, "PRO");
        assertThat(ctx(null).get("plans").size()).isEqualTo(2);
        assertThat(ctx(bearer(stranger, "OWNER")).get("plans").size()).isEqualTo(1);
        tier(sitter, "BASIC");
        assertThat(ctx(bearer(stranger, "OWNER")).get("plans").size()).isEqualTo(2);
    }

    @Test
    @DisplayName("PRD-005 AC-1.6 / AC-1.7 訂閱鎖定、停權、月額度滿 → 同一 PAUSED（方案仍可見）")
    void paused() throws Exception {
        jdbc.update("UPDATE subscriptions SET expired_at = ? WHERE sitter_id = ?", OffsetDateTime.now(clock).minusDays(3), sitter);
        assertThat(ctx(ownerToken()).get("bookability").asString()).isEqualTo("PAUSED");
        jdbc.update("UPDATE subscriptions SET expired_at = NULL WHERE sitter_id = ?", sitter);
        jdbc.update("UPDATE profiles SET kyc_status = 'SUSPENDED' WHERE user_id = ?", sitter);
        JsonNode suspended = ctx(ownerToken());
        assertThat(suspended.get("bookability").asString()).isEqualTo("PAUSED");
        assertThat(suspended.get("plans").size()).isEqualTo(2);
        jdbc.update("UPDATE profiles SET kyc_status = 'VERIFIED' WHERE user_id = ?", sitter);
        tier(sitter, "FREE");
        for (int i = 0; i < 3; i++) {
            UUID o = fx.order(owner, sitter, "CONFIRMED");
            jdbc.update("UPDATE orders SET confirmed_at = ? WHERE id = ?", OffsetDateTime.now(clock), o);
        }
        assertThat(ctx(ownerToken()).get("bookability").asString()).isEqualTo("PAUSED");
    }

    @Test
    @DisplayName("PRD-005 AC-1.8 自己的頁面 → SELF（即使鎖定且隱藏）")
    void self() throws Exception {
        jdbc.update("UPDATE profiles SET is_visible = false WHERE user_id = ?", sitter);
        jdbc.update("UPDATE subscriptions SET expired_at = ? WHERE sitter_id = ?", OffsetDateTime.now(clock).minusDays(3), sitter);
        mvc.perform(getAs("/api/sitters/{id}/booking-context", bearer(sitter, "OWNER"), sitter))
                .andExpect(status().isOk()).andExpect(jsonPath("$.bookability").value("SELF"));
    }

    @Test
    @DisplayName("PRD-005 AC-2.3 可預約區間只在 PRO / ULTIMATE 且未鎖定時回傳")
    void bookableRange() throws Exception {
        jdbc.update("UPDATE service_plans SET start_date = ?, end_date = ? WHERE id = ?", today().plusDays(5), today().plusDays(9), planA);
        JsonNode pro = ctx(null);
        assertThat(pro.get("plans").findValues("bookableFrom")).hasSize(1);
        tier(sitter, "BASIC");
        assertThat(ctx(null).get("plans").findValues("bookableFrom")).isEmpty();
    }
}
