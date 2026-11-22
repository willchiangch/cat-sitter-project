package com.petsitter.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 爭議、結案、ADMIN（SD-ORDER §10.3 PaymentDispute / DisputeFreeze / Mediation / AdminOrderQuery / Completion /
 * SubscriptionLockedOrder / AdminVoid / AdminVoidConcurrency / OrderDetailCompletionHint / SitterLedger，合併 nested）。
 */
@DisplayName("E16、E24–E30 DisputeAndAdminIntegrationTest")
class DisputeAndAdminIntegrationTest extends OrderIntegrationTestBase {

    private LocalDate d1() {
        return today().plusDays(5);
    }

    private String adminToken() {
        return bearer(admin, "ADMIN");
    }

    private ResultActions paymentDispute(UUID orderId, String token, String description) throws Exception {
        return mvc.perform(postJson("/api/orders/{o}/payment-disputes", token, key(), Map.of("description", description), orderId));
    }

    private ResultActions complete(UUID orderId) throws Exception {
        return mvc.perform(postJson("/api/orders/{o}/complete", ownerToken(), key(), Map.of(), orderId));
    }

    private ResultActions serviceDispute(UUID orderId, String description) throws Exception {
        return mvc.perform(postJson("/api/orders/{o}/service-disputes", ownerToken(), key(), Map.of("description", description), orderId));
    }

    private ResultActions mediate(UUID orderId, Object body) throws Exception {
        return mvc.perform(postJson("/api/admin/orders/{o}/mediation", adminToken(), key(), body, orderId));
    }

    /** CONFIRMED 訂單（3 行程）+ 保母發起「減少一天」的變更並由飼主同意 → AWAITING_REFUND。回傳 [orderId, refundPaymentId]。 */
    private UUID[] awaitingRefund() throws Exception {
        UUID orderId = confirmedOrder(d1(), d1().plusDays(1), d1().plusDays(2));
        UUID v1 = visitIds(orderId, "PENDING").get(0);
        UUID mod = modificationId(modify(orderId, sitterToken(), Map.of("type", "CHANGE", "reason", "x",
                "removeVisitIds", List.of(v1.toString()))));
        JsonNode r = body(mvc.perform(postJson("/api/orders/{o}/modifications/{m}/accept", ownerToken(), key(), Map.of(), orderId, mod)));
        return new UUID[]{orderId, UUID.fromString(r.get("payment").get("paymentId").asString())};
    }

    /** CONFIRMED + 新增一天變更、同意 → AWAITING_SUPPLEMENT。回傳 [orderId, supplementPaymentId]。 */
    private UUID[] awaitingSupplement() throws Exception {
        UUID orderId = confirmedOrder(d1(), d1().plusDays(1));
        UUID mod = modificationId(modify(orderId, sitterToken(), Map.of("type", "CHANGE", "reason", "x",
                "addItems", List.of(addItem(d1().plusDays(8), planA, 1, petA)))));
        JsonNode r = body(mvc.perform(postJson("/api/orders/{o}/modifications/{m}/accept", ownerToken(), key(), Map.of(), orderId, mod)));
        return new UUID[]{orderId, UUID.fromString(r.get("payment").get("paymentId").asString())};
    }

    /** IN_PROGRESS 訂單，所有行程 DONE（日誌依參數）。 */
    private UUID allDone(boolean withLog, int days) throws Exception {
        LocalDate[] dates = new LocalDate[days];
        for (int i = 0; i < days; i++) {
            dates[i] = d1().plusDays(i);
        }
        UUID orderId = confirmedOrder(dates);
        for (UUID v : visitIds(orderId, "PENDING")) {
            finishVisit(v, withLog);
        }
        return orderId;
    }

    @Nested
    @DisplayName("PaymentDisputeIntegrationTest")
    class PaymentDispute {

        @Test
        @DisplayName("PRD-024 AC-1.1 飼主於等待退款時申報：DISPUTED、disputed_at、MODIFICATION_PAYMENT_DISPUTE 待辦（payment_id）、log 含說明、通知保母")
        void ownerDuringRefund() throws Exception {
            UUID[] ids = awaitingRefund();
            JsonNode r = body(paymentDispute(ids[0], ownerToken(), " 退款沒收到 ").andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("DISPUTED")));
            assertThat(jdbc.queryForMap("SELECT type, payment_id, opened_by_role FROM admin_tasks WHERE id = ?", UUID.fromString(r.get("adminTaskId").asString())))
                    .containsEntry("type", "MODIFICATION_PAYMENT_DISPUTE").containsEntry("payment_id", ids[1]).containsEntry("opened_by_role", "OWNER");
            assertThat(jdbc.queryForObject("SELECT payload->>'description' FROM order_logs WHERE order_id = ? AND action_type = 'PAYMENT_DISPUTE_OPENED'",
                    String.class, ids[0])).isEqualTo("退款沒收到");
            assertThat(count("SELECT count(*) FROM notifications WHERE user_id = ? AND type = 'PAYMENT_DISPUTE_OPENED'", sitter)).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM notifications WHERE user_id = ?", admin)).isZero();
        }

        @Test
        @DisplayName("PRD-024 AC-1.3 補款 SUBMITTED 未滿 72h → 409 PAYMENT_STATE_CONFLICT（disputeAvailableAt）；滿 72h → 保母可申報（AC-1.2）；UNPAID → 409")
        void supplementWindow() throws Exception {
            UUID[] ids = awaitingSupplement();
            paymentDispute(ids[0], sitterToken(), "x").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PAYMENT_STATE_CONFLICT"));
            submitProof(ids[0], ids[1], ownerToken());
            paymentDispute(ids[0], sitterToken(), "x").andExpect(status().isConflict())
                    .andExpect(jsonPath("$.payload.disputeAvailableAt").exists());
            advance(Duration.ofHours(72));
            paymentDispute(ids[0], sitterToken(), "未核對").andExpect(status().isOk());
        }

        @Test
        @DisplayName("PRD-024 AC-1.4 不符條件：PENDING_PAYMENT → ORDER_STATE_CONFLICT；無 AWAITING_* → MODIFICATION_STATE_CONFLICT；AC-1.8 說明 501 字 / 空白 → 400")
        void conditions() throws Exception {
            UUID quoted = book(d1());
            quote(quoted);
            paymentDispute(quoted, ownerToken(), "x").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_STATE_CONFLICT"));
            UUID confirmed = confirmedOrder(d1());
            paymentDispute(confirmed, ownerToken(), "x").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MODIFICATION_STATE_CONFLICT"));
            paymentDispute(confirmed, ownerToken(), "字".repeat(501)).andExpect(status().isBadRequest());
            paymentDispute(confirmed, ownerToken(), "　").andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("PRD-024 AC-1.6 雙方同時申報只成功一筆、待辦只有一筆；AC-1.5 他人 403")
        void concurrentBoth() throws Exception {
            UUID[] ids = awaitingRefund();
            paymentDispute(ids[0], bearer(stranger, "OWNER"), "x").andExpect(status().isForbidden());
            int[] codes = race(() -> paymentDispute(ids[0], ownerToken(), "a"), () -> paymentDispute(ids[0], sitterToken(), "b"));
            assertThat(Arrays.stream(codes).filter(c -> c == 200).count()).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM admin_tasks WHERE order_id = ?", ids[0])).isEqualTo(1);
        }

        @Test
        @DisplayName("PRD-024 AC-1.7 申報 vs 確認退款兩執行緒：恰一方成功（不同時 DISPUTED 又 SETTLED）")
        void disputeVsVerify() throws Exception {
            UUID[] ids = awaitingRefund();
            UUID sub = submitProof(ids[0], ids[1], sitterToken());
            int[] codes = race(() -> paymentDispute(ids[0], sitterToken(), "a"), () -> verify(ids[0], ids[1], sub, ownerToken()));
            assertThat(Arrays.stream(codes).filter(c -> c == 200).count()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("DisputeFreezeIntegrationTest")
    class Freeze {

        @Test
        @DisplayName("PRD-024 AC-2.1 DISPUTED 凍結：上傳 / 確認 / 發起變更 → 409 ORDER_STATE_CONFLICT；非發起者撤回 → 403；AC-2.2 行程照常開始、主狀態不變")
        void frozen() throws Exception {
            UUID[] ids = awaitingRefund();
            paymentDispute(ids[0], ownerToken(), "x").andExpect(status().isOk());
            upload(ids[0], ids[1], sitterToken(), key(), PNG).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_STATE_CONFLICT"));
            modify(ids[0], ownerToken(), Map.of("type", "CANCEL_ALL", "reason", "x")).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("ORDER_STATE_CONFLICT"));
            UUID v = visitIds(ids[0], "PENDING").get(0);
            startVisit(v);
            assertThat(orderStatus(ids[0])).isEqualTo("DISPUTED");
        }
    }

    @Nested
    @DisplayName("MediationIntegrationTest")
    class Mediation {

        @Test
        @DisplayName("PRD-024 AC-2.3 UNPAID 退款：變更 REJECTED、payment VOID、待辦 RESOLVED、無行程服務 → O17 CANCELLED（ADMIN_MEDIATED）")
        void unpaidRefundCancelled() throws Exception {
            UUID[] ids = awaitingRefund();
            paymentDispute(ids[0], ownerToken(), "x");
            mediate(ids[0], Map.of()).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
            assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, ids[1])).isEqualTo("VOID");
            assertThat(count("SELECT count(*) FROM modification_requests WHERE order_id = ? AND status = 'REJECTED'", ids[0])).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM admin_tasks WHERE order_id = ? AND status = 'RESOLVED' AND resolution = 'ADMIN_MEDIATED'", ids[0])).isEqualTo(1);
            assertThat(jdbc.queryForMap("SELECT cancelled_by, cancel_reason_code FROM orders WHERE id = ?", ids[0]))
                    .containsEntry("cancelled_by", "ADMIN").containsEntry("cancel_reason_code", "ADMIN_MEDIATED");
            assertThat(logActions(ids[0])).contains("ORDER_MEDIATED");
        }

        @Test
        @DisplayName("PRD-024 AC-2.4 SUBMITTED 退款須標 exported（缺 → 400 paymentIds、整筆不生效）；exported → VOID + 等額 ADMIN_ADJUSTMENT（source）；有 DONE → O15 COMPLETED；手動調整超過 → 422")
        void submittedRefundExported() throws Exception {
            UUID[] ids = awaitingRefund();
            UUID done = visitIds(ids[0], "PENDING").get(0);
            finishVisit(done, true);
            submitProof(ids[0], ids[1], sitterToken());
            paymentDispute(ids[0], ownerToken(), "x").andExpect(status().isOk());
            mediate(ids[0], Map.of()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.payload.paymentIds[0]").value(ids[1].toString()));
            assertThat(orderStatus(ids[0])).isEqualTo("DISPUTED");
            mediate(ids[0], Map.of("refundDecisions", List.of(Map.of("paymentId", ids[1].toString(), "exported", true)),
                    "adjustment", Map.of("amount", 1001, "basis", "x"))).andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("INVALID_ADJUSTMENT_AMOUNT")).andExpect(jsonPath("$.payload.maxAmount").value(1000));
            JsonNode r = body(mediate(ids[0], Map.of("refundDecisions", List.of(Map.of("paymentId", ids[1].toString(), "exported", true)),
                    "adjustment", Map.of("amount", 200, "basis", "部分退費"))).andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("COMPLETED")));
            assertThat(r.get("adjustmentPaymentIds").size()).isEqualTo(2);
            assertThat(count("SELECT count(*) FROM payments WHERE order_id = ? AND purpose = 'ADMIN_ADJUSTMENT' AND source_payment_id = ? AND amount = 500",
                    ids[0], ids[1])).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT completed_by FROM orders WHERE id = ?", String.class, ids[0])).isEqualTo("ADMIN");
        }

        @Test
        @DisplayName("PRD-024 AC-2.3 有 IN_PROGRESS 行程 → 409 VISITS_NOT_FINISHED；非 DISPUTED（但可讀）→ 409 ORDER_STATE_CONFLICT；不可讀 → 403")
        void preconditions() throws Exception {
            UUID[] ids = awaitingRefund();
            mediate(ids[0], Map.of()).andExpect(status().isForbidden());
            paymentDispute(ids[0], ownerToken(), "x");
            startVisit(visitIds(ids[0], "PENDING").get(0));
            mediate(ids[0], Map.of()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VISITS_NOT_FINISHED"));
        }
    }

    @Nested
    @DisplayName("AdminOrderQueryIntegrationTest")
    class AdminQuery {

        @Test
        @DisplayName("PRD-024 AC-2.5 ADMIN 唯讀範圍：從未有待辦 403；DISPUTED 200（含快照、log、待辦、變更）；只有 RESOLVED 待辦仍可讀；寫入 E16 不因此放寬")
        void readableScope() throws Exception {
            UUID orderId = confirmedOrder(d1());
            mvc.perform(getAs("/api/admin/orders/{o}", adminToken(), orderId)).andExpect(status().isForbidden());
            UUID[] ids = awaitingRefund();
            paymentDispute(ids[0], ownerToken(), "x");
            JsonNode d = body(mvc.perform(getAs("/api/admin/orders/{o}", adminToken(), ids[0])).andExpect(status().isOk()));
            assertThat(d.get("role").asString()).isEqualTo("ADMIN");
            assertThat(d.get("logs").size()).isGreaterThan(3);
            assertThat(d.get("adminTasks").size()).isEqualTo(1);
            assertThat(d.get("modifications").size()).isEqualTo(1);
            assertThat(d.get("payments").get(0).get("accountSnapshot")).isNotNull();
            assertThat(d.get("allowedActions").toString()).contains("MEDIATE");
            mediate(ids[0], Map.of()).andExpect(status().isOk());
            mvc.perform(getAs("/api/admin/orders/{o}", adminToken(), ids[0])).andExpect(status().isOk());
            JsonNode tasks = body(mvc.perform(getAs("/api/admin/tasks?status=RESOLVED", adminToken())).andExpect(status().isOk()));
            assertThat(tasks.get("totalElements").asLong()).isEqualTo(1);
            mvc.perform(getAs("/api/admin/tasks?status=NOPE", adminToken())).andExpect(status().isBadRequest());
            mvc.perform(getAs("/api/admin/tasks", ownerToken())).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ROLE_MISMATCH"));
        }
    }

    @Nested
    @DisplayName("AdminVoidIntegrationTest")
    class AdminVoid {

        private UUID[] escalated() throws Exception {
            UUID orderId = book(d1());
            UUID pay = quote(orderId);
            UUID sub = submitProof(orderId, pay, ownerToken());
            UUID task = UUID.randomUUID();
            jdbc.update("INSERT INTO admin_tasks (id, type, order_id, payment_id, status, opened_by_role) VALUES (?, 'INITIAL_PAYMENT_UNVERIFIED', ?, ?, 'OPEN', 'SYSTEM')",
                    task, orderId, pay);
            return new UUID[]{orderId, pay, sub, task};
        }

        private ResultActions voidPay(UUID orderId, UUID pay, String reason) throws Exception {
            return mvc.perform(postJson("/api/admin/orders/{o}/payments/{p}/void", adminToken(), key(), Map.of("reason", reason), orderId, pay));
        }

        @Test
        @DisplayName("PRD-007 AC-3.6 ADMIN 作廢：O16 CANCELLED（ADMIN / ADMIN_VOIDED）、payment VOID、submission 維持 SUBMITTED 且 voided=true、待辦 ADMIN_VOIDED、雙方通知不可關閉")
        void voidSuccess() throws Exception {
            UUID[] ids = escalated();
            voidPay(ids[0], ids[1], "雙方自行處理").andExpect(status().isOk()).andExpect(jsonPath("$.cancelledBy").value("ADMIN"));
            assertThat(jdbc.queryForObject("SELECT status FROM payment_submissions WHERE id = ?", String.class, ids[2])).isEqualTo("SUBMITTED");
            JsonNode hist = body(mvc.perform(getAs("/api/orders/{o}/payments/{p}/submissions", ownerToken(), ids[0], ids[1])));
            assertThat(hist.get(0).get("voided").asBoolean()).isTrue();
            assertThat(jdbc.queryForObject("SELECT resolution FROM admin_tasks WHERE id = ?", String.class, ids[3])).isEqualTo("ADMIN_VOIDED");
            assertThat(count("SELECT count(*) FROM notifications WHERE related_order_id = ? AND type = 'PAYMENT_VOIDED_BY_ADMIN' AND mandatory", ids[0])).isEqualTo(2);
        }

        @Test
        @DisplayName("PRD-007 AC-3.7 ADMIN 限制：無 OPEN 待辦 → 403 FORBIDDEN（不取鎖）；原因空白 → 400；已作廢再作廢 → 403")
        void restrictions() throws Exception {
            UUID orderId = book(d1());
            UUID pay = quote(orderId);
            voidPay(orderId, pay, "x").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
            UUID[] ids = escalated();
            voidPay(ids[0], ids[1], " ").andExpect(status().isBadRequest());
            voidPay(ids[0], ids[1], "x").andExpect(status().isOk());
            voidPay(ids[0], ids[1], "x").andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("PRD-007 AC-3.8 保母確認 vs ADMIN 作廢兩執行緒：不會同時 CONFIRMED 與 CANCELLED")
        void verifyVsVoid() throws Exception {
            UUID[] ids = escalated();
            int[] codes = race(() -> verify(ids[0], ids[1], ids[2], sitterToken()), () -> voidPay(ids[0], ids[1], "x"));
            assertThat(Arrays.stream(codes).filter(c -> c == 200).count()).isEqualTo(1);
            String status = orderStatus(ids[0]);
            assertThat(status).isEqualTo(codes[0] == 200 ? "CONFIRMED" : "CANCELLED");
            assertThat(logActions(ids[0]).stream().filter(a -> a.equals("PAYMENT_VERIFIED") || a.equals("PAYMENT_VOIDED_BY_ADMIN")).count()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("CompletionIntegrationTest")
    class Completion {

        @Test
        @DisplayName("PRD-009 AC-1.1 確認結案：COMPLETED（OWNER）、ORDER_COMPLETED、通知保母；AC-1.4 終態不可再結案")
        void completeSuccess() throws Exception {
            UUID orderId = allDone(true, 2);
            complete(orderId).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
            assertThat(jdbc.queryForObject("SELECT completed_by FROM orders WHERE id = ?", String.class, orderId)).isEqualTo("OWNER");
            assertThat(count("SELECT count(*) FROM notifications WHERE user_id = ? AND type = 'ORDER_COMPLETED_BY_OWNER'", sitter)).isEqualTo(1);
            complete(orderId).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_STATE_CONFLICT"));
        }

        @Test
        @DisplayName("PRD-009 AC-1.2 行程或日誌未完成 → 409 VISITS_NOT_FINISHED；AC-1.3 / PRD-024 AC-2.6 變更進行中先回 MODIFICATION_IN_PROGRESS")
        void notFinished() throws Exception {
            UUID orderId = confirmedOrder(d1(), d1().plusDays(1));
            List<UUID> vs = visitIds(orderId, "PENDING");
            finishVisit(vs.get(0), false);
            complete(orderId).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VISITS_NOT_FINISHED"));
            modify(orderId, ownerToken(), Map.of("type", "CANCEL_ALL", "reason", "x")).andExpect(status().isCreated());
            complete(orderId).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MODIFICATION_IN_PROGRESS"));
            serviceDispute(orderId, "x").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MODIFICATION_IN_PROGRESS"));
        }

        @Test
        @DisplayName("PRD-009 AC-2.5 回報問題：DISPUTED、SERVICE_DISPUTE 待辦、log 存去空白說明、通知保母；AC-2.6 視窗邊界 basis + 48h → DEADLINE_PASSED")
        void serviceDisputeAndWindow() throws Exception {
            UUID orderId = allDone(false, 1);
            OffsetDateTime basis = jdbc.queryForObject("SELECT completion_basis_at FROM orders WHERE id = ?", OffsetDateTime.class, orderId);
            assertThat(basis).isNotNull();
            serviceDispute(orderId, "　毛孩受傷 ").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DISPUTED"));
            assertThat(jdbc.queryForObject("SELECT payload->>'description' FROM order_logs WHERE order_id = ? AND action_type = 'SERVICE_DISPUTE_OPENED'",
                    String.class, orderId)).isEqualTo("毛孩受傷");
            assertThat(count("SELECT count(*) FROM admin_tasks WHERE order_id = ? AND type = 'SERVICE_DISPUTE' AND payment_id IS NULL", orderId)).isEqualTo(1);
            UUID other = allDone(false, 1);
            OffsetDateTime otherBasis = jdbc.queryForObject("SELECT completion_basis_at FROM orders WHERE id = ?", OffsetDateTime.class, other);
            clock.advance(Duration.between(OffsetDateTime.now(clock), otherBasis.plusHours(48)));
            serviceDispute(other, "x").andExpect(status().isConflict()).andExpect(jsonPath("$.payload.deadline").value("DISPUTE_WINDOW_ENDS_AT"));
        }

        @Test
        @DisplayName("PRD-009 AC-2.7 前置不符：CONFIRMED → 409 ORDER_STATE_CONFLICT；行程未結束 → VISITS_NOT_FINISHED；AC-2.8 說明 501 字 → 400、他人 403")
        void serviceDisputePreconditions() throws Exception {
            UUID orderId = confirmedOrder(d1(), d1().plusDays(1));
            serviceDispute(orderId, "x").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_STATE_CONFLICT"));
            finishVisit(visitIds(orderId, "PENDING").get(0), false);
            serviceDispute(orderId, "x").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VISITS_NOT_FINISHED"));
            serviceDispute(orderId, "字".repeat(501)).andExpect(status().isBadRequest());
            mvc.perform(postJson("/api/orders/{o}/service-disputes", bearer(stranger, "OWNER"), key(), Map.of("description", "x"), orderId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("PRD-009 AC-1.5 / AC-1.6 缺 key → 400；同 key 換訂單 → 422；同 key 同訂單回放")
        void idempotency() throws Exception {
            UUID a = allDone(true, 1);
            UUID b = allDone(true, 1);
            mvc.perform(postJson("/api/orders/{o}/complete", ownerToken(), null, Map.of(), a)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.payload.field").value("Idempotency-Key"));
            String k = key();
            mvc.perform(postJson("/api/orders/{o}/complete", ownerToken(), k, Map.of(), a)).andExpect(status().isOk());
            mvc.perform(postJson("/api/orders/{o}/complete", ownerToken(), k, Map.of(), a)).andExpect(status().isOk());
            mvc.perform(postJson("/api/orders/{o}/complete", ownerToken(), k, Map.of(), b)).andExpect(status().isUnprocessableContent());
        }
    }

    @Nested
    @DisplayName("SubscriptionLockedOrderIntegrationTest")
    class SubscriptionLocked {

        @Test
        @DisplayName("PRD-024 AC-2.8 鎖定保母：發起 / 同意 / 上傳退款 / 確認 / 申報皆不回 SUBSCRIPTION_LOCKED")
        void lockedDoesNotBlock() throws Exception {
            UUID orderId = confirmedOrder(d1(), d1().plusDays(1));
            jdbc.update("UPDATE subscriptions SET expired_at = ? WHERE sitter_id = ?", OffsetDateTime.now(clock).minusDays(3), sitter);
            UUID v1 = visitIds(orderId, "PENDING").get(0);
            UUID mod = modificationId(modify(orderId, sitterToken(), Map.of("type", "CHANGE", "reason", "x", "removeVisitIds", List.of(v1.toString())))
                    .andExpect(status().isCreated()));
            JsonNode r = body(mvc.perform(postJson("/api/orders/{o}/modifications/{m}/accept", ownerToken(), key(), Map.of(), orderId, mod))
                    .andExpect(status().isOk()));
            UUID pay = UUID.fromString(r.get("payment").get("paymentId").asString());
            UUID sub = submitProof(orderId, pay, sitterToken());
            paymentDispute(orderId, ownerToken(), "x").andExpect(status().isOk());
            assertThat(sub).isNotNull();
        }
    }

    @Nested
    @DisplayName("OrderDetailCompletionHintIntegrationTest")
    class CompletionHint {

        private JsonNode detail(UUID orderId, String token) throws Exception {
            return body(mvc.perform(getAs("/api/orders/{o}", token, orderId)).andExpect(status().isOk()));
        }

        @Test
        @DisplayName("PRD-009 AC-1.2 / AC-1.3 / AC-2.2 completionHint i–v：MODIFICATION_IN_PROGRESS、VISITS_NOT_FINISHED、CARE_LOG_PENDING、CARE_LOG_EXPIRED（autoCompleteAt）、null + COMPLETE")
        void hints() throws Exception {
            UUID orderId = confirmedOrder(d1(), d1().plusDays(1));
            List<UUID> vs = visitIds(orderId, "PENDING");
            finishVisit(vs.get(0), false);
            assertThat(detail(orderId, ownerToken()).get("completionHint").get("code").asString()).isEqualTo("VISITS_NOT_FINISHED");
            UUID mod = modificationId(modify(orderId, ownerToken(), Map.of("type", "CHANGE", "reason", "x", "removeVisitIds", List.of(vs.get(1).toString()))));
            assertThat(detail(orderId, ownerToken()).get("completionHint").get("code").asString()).isEqualTo("MODIFICATION_IN_PROGRESS");
            mvc.perform(postJson("/api/orders/{o}/modifications/{m}/withdraw", ownerToken(), key(), Map.of(), orderId, mod)).andExpect(status().isOk());
            finishVisit(vs.get(1), false);
            JsonNode pending = detail(orderId, ownerToken());
            assertThat(pending.get("completionHint").get("code").asString()).isEqualTo("CARE_LOG_PENDING");
            assertThat(pending.get("allowedActions").toString()).doesNotContain("COMPLETE").contains("REPORT_SERVICE_ISSUE");
            assertThat(pending.get("disputeWindowEndsAt")).isNotNull();
            assertThat(pending.get("serverTime")).isNotNull();
            assertThat(detail(orderId, sitterToken()).has("completionHint")).isFalse();
            advance(Duration.ofHours(24));
            JsonNode expired = detail(orderId, ownerToken());
            assertThat(expired.get("completionHint").get("code").asString()).isEqualTo("CARE_LOG_EXPIRED");
            assertThat(expired.get("completionHint").get("autoCompleteAt")).isNotNull();
            clock.reset();
            UUID done = allDone(true, 1);
            JsonNode ready = detail(done, ownerToken());
            assertThat(ready.has("completionHint")).isFalse();
            assertThat(ready.get("allowedActions").toString()).contains("COMPLETE");
            assertThat(ready.get("visits").get(0).get("careLogSubmitted").asBoolean()).isTrue();
        }

        @Test
        @DisplayName("PRD-006 BR-16 報價明細三段：原報價、SETTLED 變更列（amountChange）、目前總額；SITTER 才回 sitterTier / modificationPricingMode")
        void quoteBreakdown() throws Exception {
            UUID orderId = confirmedOrder(d1(), d1().plusDays(1));
            UUID v1 = visitIds(orderId, "PENDING").get(0);
            UUID mod = modificationId(modify(orderId, sitterToken(), Map.of("type", "CHANGE", "reason", "x", "removeVisitIds", List.of(v1.toString()))));
            JsonNode r = body(mvc.perform(postJson("/api/orders/{o}/modifications/{m}/accept", ownerToken(), key(), Map.of(), orderId, mod)));
            UUID pay = UUID.fromString(r.get("payment").get("paymentId").asString());
            verify(orderId, pay, submitProof(orderId, pay, sitterToken()), ownerToken()).andExpect(status().isOk());
            JsonNode quote = detail(orderId, ownerToken()).get("quote");
            assertThat(quote.get("original").get("totalAmount").asInt()).isEqualTo(1000);
            assertThat(quote.get("adjustments").get(0).get("amountChange").asInt()).isEqualTo(-500);
            assertThat(quote.get("adjustments").get(0).get("removedVisitDates").get(0).asString()).isEqualTo(d1().toString());
            assertThat(quote.get("totalAmount").asInt()).isEqualTo(500);
            assertThat(detail(orderId, ownerToken()).has("sitterTier")).isFalse();
            assertThat(detail(orderId, sitterToken()).get("sitterTier").asString()).isEqualTo("PRO");
        }
    }

    @Nested
    @DisplayName("SitterLedgerIntegrationTest")
    class Ledger {

        @Test
        @DisplayName("PRD-009 AC-3.1 / AC-3.3 / AC-3.8 收入 = 終態訂單已收淨額（payments 加總）；非終態不計；淨額 0 的 CANCELLED 不列；AC-3.4 serverMonth；AC-3.5 month 缺 / 錯 → 400、OWNER → 403")
        void ledger() throws Exception {
            String month = OffsetDateTime.now(clock).atZoneSameInstant(TAIPEI).toLocalDate().toString().substring(0, 7);
            UUID completed = allDone(true, 2);
            complete(completed).andExpect(status().isOk());
            confirmedOrder(d1());
            UUID withdrawn = book(d1());
            mvc.perform(postJson("/api/orders/{id}/withdraw", ownerToken(), key(), Map.of("expectedStatus", "PENDING"), withdrawn));
            JsonNode r = body(mvc.perform(getAs("/api/orders/sitter/ledger?month=" + month, sitterToken())).andExpect(status().isOk()));
            assertThat(r.get("totalIncome").asInt()).isEqualTo(1000);
            assertThat(r.get("orders").size()).isEqualTo(1);
            assertThat(r.get("orders").get(0).get("netPaid").asInt()).isEqualTo(1000);
            assertThat(r.get("serverMonth").asString()).isEqualTo(month);
            JsonNode empty = body(mvc.perform(getAs("/api/orders/sitter/ledger?month=2001-01", sitterToken())));
            assertThat(empty.get("totalIncome").asInt()).isZero();
            assertThat(empty.get("orders").size()).isZero();
            mvc.perform(getAs("/api/orders/sitter/ledger", sitterToken())).andExpect(status().isBadRequest());
            mvc.perform(getAs("/api/orders/sitter/ledger?month=2026-1", sitterToken())).andExpect(status().isBadRequest());
            mvc.perform(getAs("/api/orders/sitter/ledger?month=" + month, ownerToken())).andExpect(status().isForbidden());
        }
    }
}
