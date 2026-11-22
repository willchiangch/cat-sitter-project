package com.petsitter.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** E5 評估頁（PRD-006 AC-1.1–1.5、SD-ORDER §10.3 EvaluationQueryIntegrationTest）。 */
@DisplayName("E5 EvaluationQueryIntegrationTest")
class EvaluationQueryIntegrationTest extends OrderIntegrationTestBase {

    private JsonNode eval(UUID orderId) throws Exception {
        return body(mvc.perform(getAs("/api/orders/{id}/evaluation", sitterToken(), orderId)).andExpect(status().isOk()));
    }

    @Test
    @DisplayName("PRD-006 AC-1.1 評估頁內容：群組、日期、毛孩（已刪除者仍列並標示 deleted）、baseAmount、reviewExpiresAt")
    void content() throws Exception {
        UUID orderId = book(today().plusDays(3), today().plusDays(4));
        jdbc.update("UPDATE pets SET is_deleted = true WHERE id = ?", petA);
        JsonNode r = eval(orderId);
        assertThat(r.get("baseAmount").asInt()).isEqualTo(1000);
        assertThat(r.get("groups").get(0).get("dates").size()).isEqualTo(2);
        JsonNode pet = r.get("groups").get(0).get("pets").get(0);
        assertThat(pet.get("petId").asString()).isEqualTo(petA.toString());
        assertThat(pet.get("deleted").asBoolean()).isTrue();
        assertThat(r.get("reviewExpiresAt")).isNotNull();
        assertThat(r.get("serverTime")).isNotNull();
    }

    @Test
    @DisplayName("PRD-006 AC-1.3 免填原因與熟客標記即時判定（送單後另一筆訂單結案 → isRegularOwner=true，免填原因不變）")
    void regularOwnerIsLive() throws Exception {
        UUID orderId = book(today().plusDays(3));
        assertThat(eval(orderId).get("isRegularOwner").asBoolean()).isFalse();
        fx.order(owner, sitter, "COMPLETED");
        JsonNode r = eval(orderId);
        assertThat(r.get("isRegularOwner").asBoolean()).isTrue();
        assertThat(r.get("questionnaire").get("exemptReason").asString()).isEqualTo("NO_ACTIVE_QUESTIONS");
    }

    @Test
    @DisplayName("PRD-006 AC-1.5 預檢：鎖定 / 額度滿 / 無收款帳號 / 評估逾期 / 方案可否調價，皆以同一 serverTime 計算")
    void precheck() throws Exception {
        UUID orderId = book(today().plusDays(3));
        JsonNode ok = eval(orderId).get("precheck");
        assertThat(ok.get("subscriptionLocked").asBoolean()).isFalse();
        assertThat(ok.get("quotaReached").asBoolean()).isFalse();
        assertThat(ok.get("payoutAccountMissing").asBoolean()).isFalse();
        assertThat(ok.get("planAllowsAdjustment").asBoolean()).isTrue();
        jdbc.update("UPDATE profiles SET bank_account_info = NULL WHERE user_id = ?", sitter);
        tier(sitter, "FREE");
        for (int i = 0; i < 3; i++) {
            UUID o = fx.order(owner, sitter, "CONFIRMED");
            jdbc.update("UPDATE orders SET confirmed_at = ? WHERE id = ?", OffsetDateTime.now(clock), o);
        }
        jdbc.update("UPDATE subscriptions SET expired_at = ? WHERE sitter_id = ?", OffsetDateTime.now(clock).minusDays(3), sitter);
        advance(Duration.ofDays(4));
        JsonNode bad = eval(orderId).get("precheck");
        assertThat(bad.get("subscriptionLocked").asBoolean()).isTrue();
        assertThat(bad.get("payoutAccountMissing").asBoolean()).isTrue();
        assertThat(bad.get("reviewExpired").asBoolean()).isTrue();
        assertThat(bad.get("planAllowsAdjustment").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("PRD-006 AC-1.4 評估頁越權：他人保母 403 FORBIDDEN、OWNER 403 ROLE_MISMATCH、不存在 404、未登入 401")
    void access() throws Exception {
        UUID orderId = book(today().plusDays(3));
        UUID other = fx.user("os-" + UUID.randomUUID() + "@test.com", "SITTER");
        mvc.perform(getAs("/api/orders/{id}/evaluation", bearer(other, "SITTER"), orderId))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(getAs("/api/orders/{id}/evaluation", ownerToken(), orderId))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ROLE_MISMATCH"));
        mvc.perform(getAs("/api/orders/{id}/evaluation", sitterToken(), UUID.randomUUID())).andExpect(status().isNotFound());
        mvc.perform(getAs("/api/orders/{id}/evaluation", null, orderId)).andExpect(status().isUnauthorized());
    }
}
