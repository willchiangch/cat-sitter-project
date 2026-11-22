package com.petsitter.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 付款前取消類（SD-ORDER §10.3 SitterDeclineIntegrationTest / OrderWithdrawIntegrationTest /
 * OrderWithdrawConcurrencyIntegrationTest / SitterCancelIntegrationTest，合併為 nested 類以共用 context）。
 */
@DisplayName("E7–E9 OrderCancelIntegrationTest")
class OrderCancelIntegrationTest extends OrderIntegrationTestBase {

    private org.springframework.test.web.servlet.ResultActions decline(UUID orderId, String reason) throws Exception {
        return mvc.perform(postJson("/api/orders/{id}/decline", sitterToken(), key(), Map.of("reason", reason), orderId));
    }

    private org.springframework.test.web.servlet.ResultActions withdraw(UUID orderId, Map<String, Object> body) throws Exception {
        return mvc.perform(postJson("/api/orders/{id}/withdraw", ownerToken(), key(), body, orderId));
    }

    private org.springframework.test.web.servlet.ResultActions sitterCancel(UUID orderId, String reason) throws Exception {
        return mvc.perform(postJson("/api/orders/{id}/sitter-cancel", sitterToken(), key(), Map.of("reason", reason), orderId));
    }

    @Nested
    @DisplayName("SitterDeclineIntegrationTest")
    class SitterDecline {

        @Test
        @DisplayName("PRD-027 AC-1.1 婉拒成功：CANCELLED / SITTER / SITTER_DECLINED、行程取消、ORDER_REJECTED、雙方通知；AC-1.5 isDeclinedBySitter 依據")
        void declineSuccess() throws Exception {
            UUID orderId = book(today().plusDays(5));
            decline(orderId, " 當週已滿 ").andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CANCELLED")).andExpect(jsonPath("$.cancelledBy").value("SITTER"));
            Map<String, Object> o = jdbc.queryForMap("SELECT * FROM orders WHERE id = ?", orderId);
            assertThat(o.get("cancel_reason_code")).isEqualTo("SITTER_DECLINED");
            assertThat(o.get("cancel_reason")).isEqualTo("當週已滿");
            assertThat(count("SELECT count(*) FROM visits WHERE order_id = ? AND status = 'CANCELLED'", orderId)).isEqualTo(1);
            assertThat(logActions(orderId)).containsExactly("ORDER_CREATED", "ORDER_REJECTED");
            assertThat(count("SELECT count(*) FROM notifications WHERE related_order_id = ? AND type IN ('BOOKING_DECLINED','BOOKING_DECLINED_SELF')", orderId)).isEqualTo(2);
        }

        @Test
        @DisplayName("PRD-027 AC-1.2 / AC-1.3 原因：只有空白（含 U+3000 / U+00A0）→ 400；201 字 → 400；非 PENDING → 409；他人 403")
        void reasonRulesAndErrors() throws Exception {
            UUID orderId = book(today().plusDays(5));
            decline(orderId, "　  ").andExpect(status().isBadRequest()).andExpect(jsonPath("$.payload.field").value("reason"));
            decline(orderId, "字".repeat(201)).andExpect(status().isBadRequest());
            UUID other = fx.user("os-" + UUID.randomUUID() + "@test.com", "SITTER");
            mvc.perform(postJson("/api/orders/{id}/decline", bearer(other, "SITTER"), key(), Map.of("reason", "x"), orderId))
                    .andExpect(status().isForbidden());
            decline(orderId, " " + "字".repeat(200) + " ").andExpect(status().isOk());
            decline(orderId, "x").andExpect(status().isConflict()).andExpect(jsonPath("$.payload.currentStatus").value("CANCELLED"));
        }

        @Test
        @DisplayName("PRD-027 AC-1.6 / AC-1.7 鎖定、額度滿、期限已過仍可婉拒")
        void declineIgnoresLockQuotaDeadline() throws Exception {
            UUID orderId = book(today().plusDays(1));
            tier(sitter, "FREE");
            jdbc.update("UPDATE subscriptions SET expired_at = now() - interval '5 days' WHERE sitter_id = ?", sitter);
            advance(Duration.ofDays(3));
            decline(orderId, "來不及").andExpect(status().isOk());
        }

        @Test
        @DisplayName("PRD-027 AC-1.3 訂單列被持有 > 3 秒 → 409 ORDER_BUSY + Retry-After: 1")
        void busy() throws Exception {
            UUID orderId = book(today().plusDays(5));
            CompletableFuture<Void> holder = holdOrderLock(orderId, Duration.ofSeconds(4));
            decline(orderId, "x").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_BUSY"))
                    .andExpect(header().string("Retry-After", "1"));
            holder.join();
            assertThat(orderStatus(orderId)).isEqualTo("PENDING");
        }
    }

    @Nested
    @DisplayName("OrderWithdrawIntegrationTest")
    class Withdraw {

        @Test
        @DisplayName("PRD-026 AC-1.1 評估中撤回：OWNER / OWNER_WITHDRAWN、原因空白存 null、ORDER_WITHDRAWN（fromStatus）")
        void withdrawPending() throws Exception {
            UUID orderId = book(today().plusDays(5));
            withdraw(orderId, Map.of("expectedStatus", "PENDING", "reason", "　")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.cancelledBy").value("OWNER"));
            Map<String, Object> o = jdbc.queryForMap("SELECT * FROM orders WHERE id = ?", orderId);
            assertThat(o.get("cancel_reason_code")).isEqualTo("OWNER_WITHDRAWN");
            assertThat(o.get("cancel_reason")).isNull();
            assertThat(jdbc.queryForObject("SELECT payload->>'fromStatus' FROM order_logs WHERE order_id = ? AND action_type = 'ORDER_WITHDRAWN'",
                    String.class, orderId)).isEqualTo("PENDING");
        }

        @Test
        @DisplayName("PRD-026 AC-1.2 報價後付款前撤回（O6）：INITIAL payment VOID")
        void withdrawPendingPayment() throws Exception {
            UUID orderId = book(today().plusDays(5));
            UUID payment = quote(orderId);
            withdraw(orderId, Map.of("expectedStatus", "PENDING_PAYMENT")).andExpect(status().isOk());
            assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, payment)).isEqualTo("VOID");
            assertThat(jdbc.queryForObject("SELECT void_reason_code FROM payments WHERE id = ?", String.class, payment)).isEqualTo("OWNER_WITHDRAWN");
        }

        @Test
        @DisplayName("PRD-026 AC-1.3 已上傳憑證不可撤回 → 409 PAYMENT_ALREADY_SUBMITTED；AC-1.4 付款後 / 終態 → 409 ORDER_STATE_CONFLICT")
        void notWithdrawable() throws Exception {
            UUID orderId = book(today().plusDays(5));
            UUID payment = quote(orderId);
            UUID sub = submitProof(orderId, payment, ownerToken());
            withdraw(orderId, Map.of("expectedStatus", "PENDING_PAYMENT")).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PAYMENT_ALREADY_SUBMITTED"));
            verify(orderId, payment, sub, sitterToken());
            withdraw(orderId, Map.of("expectedStatus", "CONFIRMED")).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("ORDER_STATE_CONFLICT"));
        }

        @Test
        @DisplayName("PRD-026 AC-1.5 期限已過仍可撤回；AC-1.7 expectedStatus 與現況不符 → 409；AC-1.8 expectedStatus 缺 / 非法 → 400")
        void deadlineAndExpectedStatus() throws Exception {
            UUID orderId = book(today().plusDays(1));
            withdraw(orderId, Map.of()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.payload.field").value("expectedStatus"));
            withdraw(orderId, Map.of("expectedStatus", "WHATEVER")).andExpect(status().isBadRequest());
            withdraw(orderId, Map.of("expectedStatus", "PENDING_PAYMENT")).andExpect(status().isConflict());
            advance(Duration.ofDays(3));
            withdraw(orderId, Map.of("expectedStatus", "PENDING")).andExpect(status().isOk());
        }

        @Test
        @DisplayName("PRD-026 AC-1.6 越權與角色：他人 403 FORBIDDEN、SITTER token 403 ROLE_MISMATCH")
        void access() throws Exception {
            UUID orderId = book(today().plusDays(5));
            mvc.perform(postJson("/api/orders/{id}/withdraw", bearer(stranger, "OWNER"), key(), Map.of("expectedStatus", "PENDING"), orderId))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
            mvc.perform(postJson("/api/orders/{id}/withdraw", sitterToken(), key(), Map.of("expectedStatus", "PENDING"), orderId))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ROLE_MISMATCH"));
        }
    }

    @Nested
    @DisplayName("OrderWithdrawConcurrencyIntegrationTest")
    class WithdrawConcurrency {

        @Test
        @DisplayName("PRD-026 AC-1.7 撤回 vs 婉拒兩執行緒：恰一方成功，cancelledBy 為先到者")
        void withdrawVsDecline() throws Exception {
            UUID orderId = book(today().plusDays(5));
            int[] codes = race(() -> withdraw(orderId, Map.of("expectedStatus", "PENDING")), () -> decline(orderId, "滿了"));
            assertThat(Arrays.stream(codes).filter(c -> c == 200).count()).isEqualTo(1);
            String by = jdbc.queryForObject("SELECT cancelled_by FROM orders WHERE id = ?", String.class, orderId);
            assertThat(by).isEqualTo(codes[0] == 200 ? "OWNER" : "SITTER");
            assertThat(logActions(orderId).stream().filter(a -> !a.equals("ORDER_CREATED")).count()).isEqualTo(1);
        }

        @Test
        @DisplayName("PRD-026 AC-1.7 情境 3 訂單列被持有 > 3 秒 → 409 ORDER_BUSY + Retry-After: 1")
        void busy() throws Exception {
            UUID orderId = book(today().plusDays(5));
            CompletableFuture<Void> holder = holdOrderLock(orderId, Duration.ofSeconds(4));
            withdraw(orderId, Map.of("expectedStatus", "PENDING")).andExpect(status().isConflict())
                    .andExpect(header().string("Retry-After", "1"));
            holder.join();
        }
    }

    @Nested
    @DisplayName("SitterCancelIntegrationTest")
    class SitterCancel {

        @Test
        @DisplayName("PRD-027 AC-3.1 付款前取消：SITTER_CANCELLED、payment VOID、行程取消、ORDER_CANCELLED_BY_SITTER（paymentId）")
        void cancelSuccess() throws Exception {
            UUID orderId = book(today().plusDays(5));
            UUID payment = quote(orderId);
            sitterCancel(orderId, "臨時有事").andExpect(status().isOk());
            assertThat(jdbc.queryForObject("SELECT cancel_reason_code FROM orders WHERE id = ?", String.class, orderId)).isEqualTo("SITTER_CANCELLED");
            assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, payment)).isEqualTo("VOID");
            assertThat(jdbc.queryForObject("SELECT payload->>'paymentId' FROM order_logs WHERE order_id = ? AND action_type = 'ORDER_CANCELLED_BY_SITTER'",
                    String.class, orderId)).isEqualTo(payment.toString());
        }

        @Test
        @DisplayName("PRD-027 AC-3.2 已上傳憑證 → 409 PAYMENT_ALREADY_SUBMITTED；駁回後可取消；AC-3.3 PENDING → 409 ORDER_STATE_CONFLICT")
        void submittedThenRejected() throws Exception {
            UUID pending = book(today().plusDays(5));
            sitterCancel(pending, "x").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_STATE_CONFLICT"));
            UUID payment = quote(pending);
            UUID sub = submitProof(pending, payment, ownerToken());
            sitterCancel(pending, "x").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PAYMENT_ALREADY_SUBMITTED"));
            mvc.perform(postJson("/api/orders/{o}/payments/{p}/reject", sitterToken(), key(),
                    Map.of("submissionId", sub.toString(), "reason", "金額不符"), pending, payment)).andExpect(status().isOk());
            sitterCancel(pending, "x").andExpect(status().isOk());
        }

        @Test
        @DisplayName("PRD-027 AC-3.4 原因必填、他人 403；AC-3.6 同 key 回放、鎖定不擋；AC-3.7 付款期限後仍可取消")
        void reasonReplayAndDeadline() throws Exception {
            UUID orderId = book(today().plusDays(5));
            quote(orderId);
            sitterCancel(orderId, " ").andExpect(status().isBadRequest());
            UUID other = fx.user("os-" + UUID.randomUUID() + "@test.com", "SITTER");
            mvc.perform(postJson("/api/orders/{id}/sitter-cancel", bearer(other, "SITTER"), key(), Map.of("reason", "x"), orderId))
                    .andExpect(status().isForbidden());
            jdbc.update("UPDATE subscriptions SET expired_at = now() - interval '5 days' WHERE sitter_id = ?", sitter);
            advance(Duration.ofDays(5));
            String k = key();
            String first = mvc.perform(postJson("/api/orders/{id}/sitter-cancel", sitterToken(), k, Map.of("reason", "x"), orderId))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            String second = mvc.perform(postJson("/api/orders/{id}/sitter-cancel", sitterToken(), k, Map.of("reason", "x"), orderId))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(json.readTree(second)).isEqualTo(json.readTree(first));
            assertThat(count("SELECT count(*) FROM order_logs WHERE order_id = ? AND action_type = 'ORDER_CANCELLED_BY_SITTER'", orderId)).isEqualTo(1);
        }

        @Test
        @DisplayName("PRD-027 AC-3.4 訂單列被持有 > 3 秒 → 409 ORDER_BUSY + Retry-After: 1")
        void busy() throws Exception {
            UUID orderId = book(today().plusDays(5));
            quote(orderId);
            CompletableFuture<Void> holder = holdOrderLock(orderId, Duration.ofSeconds(4));
            sitterCancel(orderId, "x").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_BUSY"));
            holder.join();
        }
    }
}
