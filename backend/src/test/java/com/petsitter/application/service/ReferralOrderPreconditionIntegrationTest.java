package com.petsitter.application.service;

import com.petsitter.order.OrderIntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PRD-027 BR-3 / AC-1.5、SD-ORDER M21（{@code OrderQueryService.isDeclinedBySitter}）、SD-010 §1.3 B：訂單關聯轉介的前置條件。
 * 訂單須 CANCELLED、cancelledBy=SITTER、有 ORDER_REJECTED log（E7 婉拒）且呼叫者為該訂單保母；否則不寫 referral_logs。
 * 真 JWT + 真 filter chain，打 {@code POST /api/sitter/trust-circle/referrals}。
 */
@DisplayName("PRD-027 BR-3 ReferralOrderPreconditionIntegrationTest")
class ReferralOrderPreconditionIntegrationTest extends OrderIntegrationTestBase {

    private UUID partner;

    @BeforeEach
    void trustPartner() {
        partner = fx.user("partner-" + UUID.randomUUID() + "@test.com", "SITTER");
        fx.profile(partner, "SITTER", "VERIFIED");
        jdbc.update("INSERT INTO trust_relationships (id, requester_id, target_id, status) VALUES (?, ?, ?, 'ACCEPTED')",
                UUID.randomUUID(), sitter, partner);
    }

    private ResultActions refer(UUID orderId, String token) throws Exception {
        return mvc.perform(postJson("/api/sitter/trust-circle/referrals", token, null,
                Map.of("orderId", orderId.toString(), "recommendedSitterIds", List.of(partner.toString()), "message", "推薦"), orderId));
    }

    private int referrals() {
        return count("SELECT count(*) FROM referral_logs");
    }

    private void decline(UUID orderId) throws Exception {
        mvc.perform(postJson("/api/orders/{id}/decline", sitterToken(), key(), Map.of("reason", "當週已滿"), orderId))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("PRD-027 AC-1.5 保母婉拒（E7，ORDER_REJECTED）的訂單 → 轉介 200，寫 referral_logs（order_id、owner 由訂單反查）")
    void declinedBySitterAllowed() throws Exception {
        UUID orderId = book(today().plusDays(5));
        decline(orderId);
        refer(orderId, sitterToken()).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCESS"));
        assertThat(referrals()).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT order_id, owner_id FROM referral_logs"))
                .containsEntry("order_id", orderId).containsEntry("owner_id", owner);
    }

    @Test
    @DisplayName("PRD-027 BR-3 同一保母、非婉拒狀態的訂單（PENDING / CONFIRMED / 飼主撤回 / 保母付款前取消無 ORDER_REJECTED）→ 409 STATE_CONFLICT，不寫轉介")
    void otherStatesRejected() throws Exception {
        UUID pending = book(today().plusDays(5));
        refer(pending, sitterToken()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STATE_CONFLICT"));

        UUID confirmed = confirmedOrder(today().plusDays(6));
        refer(confirmed, sitterToken()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STATE_CONFLICT"));

        UUID withdrawn = book(today().plusDays(7));
        mvc.perform(postJson("/api/orders/{id}/withdraw", ownerToken(), key(), Map.of("expectedStatus", "PENDING"), withdrawn))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT cancelled_by FROM orders WHERE id = ?", String.class, withdrawn)).isEqualTo("OWNER");
        refer(withdrawn, sitterToken()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STATE_CONFLICT"));

        UUID sitterCancelled = book(today().plusDays(8));
        quote(sitterCancelled);
        mvc.perform(postJson("/api/orders/{id}/sitter-cancel", sitterToken(), key(), Map.of("reason", "臨時有事"), sitterCancelled))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT cancelled_by FROM orders WHERE id = ?", String.class, sitterCancelled)).isEqualTo("SITTER");
        refer(sitterCancelled, sitterToken()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STATE_CONFLICT"));

        assertThat(referrals()).isZero();
    }

    @Test
    @DisplayName("SD-010 §1.3 B 他人保母的訂單（即使已被婉拒）→ 403 FORBIDDEN；訂單不存在 → 404；不寫轉介")
    void otherSitterForbidden() throws Exception {
        UUID orderId = book(today().plusDays(5));
        decline(orderId);
        UUID otherSitter = fx.user("other-" + UUID.randomUUID() + "@test.com", "SITTER");
        jdbc.update("INSERT INTO trust_relationships (id, requester_id, target_id, status) VALUES (?, ?, ?, 'ACCEPTED')",
                UUID.randomUUID(), otherSitter, partner);
        refer(orderId, bearer(otherSitter, "SITTER")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        refer(UUID.randomUUID(), sitterToken()).andExpect(status().isNotFound());
        assertThat(referrals()).isZero();
    }
}
