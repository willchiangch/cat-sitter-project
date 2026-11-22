package com.petsitter.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** E1 冪等（SD-ORDER §10.2、PRD-005 AC-3.4）。 */
@DisplayName("E1 BookingIdempotencyIntegrationTest")
class BookingIdempotencyIntegrationTest extends OrderIntegrationTestBase {

    @Test
    @DisplayName("PRD-005 AC-3.4 同 key 同內容 → 回放 201、同 orderId，訂單 / log / 通知數不變；JSON key 順序與空白不同視為同內容")
    void replay() throws Exception {
        String k = key();
        LocalDate d = today().plusDays(3);
        Map<String, Object> body = bookingBody(sitter, List.of(d), 1, 500, petA);
        JsonNode first = body(mvc.perform(postJson("/api/orders", ownerToken(), k, body)).andExpect(status().isCreated()));
        String reordered = "{ \"expectedAmount\": 500,  \"groups\": [{\"petIds\":[\"" + petA + "\"],\"dailyCount\":1,\"dates\":[\""
                + d + "\"],\"planId\":\"" + planA + "\"}], \"sitterId\": \"" + sitter + "\" }";
        JsonNode second = body(mvc.perform(postJson("/api/orders", ownerToken(), k, reordered)).andExpect(status().isCreated()));
        assertThat(second.get("orderId")).isEqualTo(first.get("orderId"));
        assertThat(count("SELECT count(*) FROM orders")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM order_logs WHERE action_type = 'ORDER_CREATED'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM notifications WHERE type = 'NEW_BOOKING_REQUEST'")).isEqualTo(1);
    }

    @Test
    @DisplayName("PRD-005 AC-3.4 同 key 不同內容（日期 / 毛孩）→ 422 IDEMPOTENCY_KEY_REUSED；缺 key → 400 field=Idempotency-Key")
    void reusedAndMissing() throws Exception {
        String k = key();
        mvc.perform(postJson("/api/orders", ownerToken(), k, bookingBody(sitter, List.of(today().plusDays(3)), 1, 500, petA)))
                .andExpect(status().isCreated());
        mvc.perform(postJson("/api/orders", ownerToken(), k, bookingBody(sitter, List.of(today().plusDays(4)), 1, 500, petA)))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        mvc.perform(postJson("/api/orders", ownerToken(), k, bookingBody(sitter, List.of(today().plusDays(3)), 1, 500, petB)))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(postJson("/api/orders", ownerToken(), null, bookingBody(sitter, List.of(today().plusDays(3)), 1, 500, petA)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.payload.field").value("Idempotency-Key"));
    }

    @Test
    @DisplayName("PRD-005 AC-3.4 處理中 → 409 IDEMPOTENCY_IN_PROGRESS；locked_until 過期後同 key 接手成功")
    void inProgressAndTakeover() throws Exception {
        String k = key();
        Map<String, Object> body = bookingBody(sitter, List.of(today().plusDays(3)), 1, 500, petA);
        mvc.perform(postJson("/api/orders", ownerToken(), k, body)).andExpect(status().isCreated());
        // 模擬第一次仍在處理：把紀錄改回 PROCESSING（locked_until 未過）
        jdbc.update("UPDATE idempotency_records SET status = 'PROCESSING', locked_until = now() + interval '30 seconds', "
                + "response_status = NULL, response_body = NULL WHERE idempotency_key = ?", k);
        mvc.perform(postJson("/api/orders", ownerToken(), k, body))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_IN_PROGRESS"));
        advance(Duration.ofSeconds(31));
        mvc.perform(postJson("/api/orders", ownerToken(), k, body)).andExpect(status().isCreated());
        assertThat(count("SELECT count(*) FROM orders")).isEqualTo(2);
    }

    @Test
    @DisplayName("PRD-005 AC-3.4 業務錯誤（409 PRICE_CHANGED）被記錄並回放；換 key 依當下狀態處理")
    void businessErrorReplayed() throws Exception {
        String k = key();
        Map<String, Object> wrong = bookingBody(sitter, List.of(today().plusDays(3)), 1, 1, petA);
        mvc.perform(postJson("/api/orders", ownerToken(), k, wrong)).andExpect(status().isConflict());
        jdbc.update("UPDATE service_plans SET price = 1 WHERE id = ?", planA);
        mvc.perform(postJson("/api/orders", ownerToken(), k, wrong))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PRICE_CHANGED"));
        mvc.perform(postJson("/api/orders", ownerToken(), key(), wrong)).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("PRD-005 AC-3.4 同 key 不同使用者各自獨立（範圍 = 使用者 + 路由 + key）")
    void scopePerUser() throws Exception {
        String k = key();
        UUID strangerPet = fx.pet(stranger, "他的貓");
        mvc.perform(postJson("/api/orders", ownerToken(), k, bookingBody(sitter, List.of(today().plusDays(3)), 1, 500, petA)))
                .andExpect(status().isCreated());
        mvc.perform(postJson("/api/orders", bearer(stranger, "OWNER"), k, bookingBody(sitter, List.of(today().plusDays(3)), 1, 500, strangerPet)))
                .andExpect(status().isCreated());
        assertThat(count("SELECT count(*) FROM orders")).isEqualTo(2);
    }
}
