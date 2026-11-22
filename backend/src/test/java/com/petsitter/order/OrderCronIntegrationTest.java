package com.petsitter.order;

import com.petsitter.application.service.EmailService;
import com.petsitter.application.service.MediaStorageService;
import com.petsitter.application.service.order.OrderCronItemProcessor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 排程 C1–C11（SD-ORDER §8.1、§10.3 InternalCronSecurity / ReviewExpiryCron / PaymentCron / ModificationCron /
 * SupplementExpiry / CompletionBasis / OrphanProofSweep，及 C7 / C9 / C11 基本案例，合併 nested）。
 */
@DisplayName("C1–C11 OrderCronIntegrationTest")
@ExtendWith(OutputCaptureExtension.class)
class OrderCronIntegrationTest extends OrderIntegrationTestBase {

    static final String SECRET = "local-secret-123";

    @MockitoSpyBean
    private OrderCronItemProcessor processor;
    @MockitoSpyBean
    private MediaStorageService storage;
    @MockitoSpyBean
    private EmailService emailService;

    private JsonNode cron(String path) throws Exception {
        return body(mvc.perform(post("/api/internal/cron/" + path).header("X-Internal-Secret", SECRET)).andExpect(status().isOk()));
    }

    private ResultActions cronRaw(String path, String query) throws Exception {
        return mvc.perform(post("/api/internal/cron/" + path + (query == null ? "" : "?" + query)).header("X-Internal-Secret", SECRET));
    }

    private void counts(JsonNode r, int processed, int skipped, int failed) {
        assertThat(r.get("processed").asInt()).as("processed").isEqualTo(processed);
        assertThat(r.get("skipped").asInt()).as("skipped").isEqualTo(skipped);
        assertThat(r.get("failed").asInt()).as("failed").isEqualTo(failed);
    }

    @Nested
    @DisplayName("InternalCronSecurityTest")
    class Security {

        @Test
        @DisplayName("PRD-027 AC-2.4 C1–C11 缺 / 錯 secret、一般使用者 token → 403 FORBIDDEN（§6.1 格式），log 不含 secret")
        void forbidden(CapturedOutput output) throws Exception {
            for (String path : List.of("orders/review-expiry", "payments/reminder", "payments/initial-expiry", "payments/escalation",
                    "modifications/expiry", "modifications/supplement-expiry", "visits/zombie", "orders/auto-complete",
                    "idempotency/cleanup", "storage/payment-proof-orphans", "notifications/email-dispatch")) {
                mvc.perform(post("/api/internal/cron/" + path)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
                mvc.perform(post("/api/internal/cron/" + path).header("X-Internal-Secret", "wrong-secret-value-xyz"))
                        .andExpect(status().isForbidden());
                mvc.perform(post("/api/internal/cron/" + path).header("Authorization", sitterToken())).andExpect(status().isForbidden());
            }
            assertThat(output.getAll()).doesNotContain("wrong-secret-value-xyz").doesNotContain(SECRET);
        }

        @Test
        @DisplayName("§6.3 orderId：非測試帳號訂單 → 404；C9 / C10 帶 orderId → 400")
        void orderIdScope() throws Exception {
            UUID orderId = book(today().plusDays(3));
            cronRaw("orders/review-expiry", "orderId=" + orderId).andExpect(status().isNotFound());
            cronRaw("idempotency/cleanup", "orderId=" + orderId).andExpect(status().isBadRequest());
            cronRaw("storage/payment-proof-orphans", "orderId=" + orderId).andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("ReviewExpiryCronIntegrationTest")
    class ReviewExpiry {

        @Test
        @DisplayName("PRD-027 AC-2.1 / AC-2.2 到期（等於 review_expires_at 即處理）→ O4 CANCELLED（SYSTEM / REVIEW_EXPIRED）、ORDER_REVIEW_EXPIRED、雙方通知；未到期不動")
        void expires() throws Exception {
            LocalDate first = today().plusDays(2);
            UUID orderId = book(first);
            OffsetDateTime exp = jdbc.queryForObject("SELECT review_expires_at FROM orders WHERE id = ?", OffsetDateTime.class, orderId);
            clock.advance(Duration.between(OffsetDateTime.now(clock), exp).minusSeconds(1));
            counts(cron("orders/review-expiry"), 0, 0, 0);
            advance(Duration.ofSeconds(1));
            counts(cron("orders/review-expiry"), 1, 0, 0);
            assertThat(jdbc.queryForMap("SELECT status, cancelled_by, cancel_reason_code, cancel_reason FROM orders WHERE id = ?", orderId))
                    .containsEntry("status", "CANCELLED").containsEntry("cancelled_by", "SYSTEM")
                    .containsEntry("cancel_reason_code", "REVIEW_EXPIRED").containsEntry("cancel_reason", "保母未於期限內回覆");
            assertThat(jdbc.queryForObject("SELECT operator_role FROM order_logs WHERE order_id = ? AND action_type = 'ORDER_REVIEW_EXPIRED'",
                    String.class, orderId)).isEqualTo("SYSTEM");
            assertThat(count("SELECT count(*) FROM notifications WHERE related_order_id = ? AND type LIKE 'REVIEW_EXPIRED_%'", orderId)).isEqualTo(2);
            counts(cron("orders/review-expiry"), 0, 0, 0);
        }

        @Test
        @DisplayName("PRD-027 AC-2.3 候選被另一交易持有 → SKIP LOCKED 計 skipped、下一輪處理；單筆例外計 failed 不影響其他筆；撤回先到者不再出現")
        void skipLockedAndFailures() throws Exception {
            UUID a = book(today().plusDays(1));
            UUID b = book(today().plusDays(1));
            UUID c = book(today().plusDays(1));
            mvc.perform(postJson("/api/orders/{id}/withdraw", ownerToken(), key(), Map.of("expectedStatus", "PENDING"), c));
            advance(Duration.ofDays(2));
            CompletableFuture<Void> holder = holdOrderLock(a, Duration.ofSeconds(3));
            doAnswer(inv -> {
                throw new IllegalStateException("boom");
            }).when(processor).reviewExpiry(eq(b));
            counts(cron("orders/review-expiry"), 0, 1, 1);
            holder.join();
            org.mockito.Mockito.reset(processor);
            counts(cron("orders/review-expiry"), 2, 0, 0);
        }

        @Test
        @DisplayName("PRD-026 AC-1.5 / PRD-027 AC-1.7 期限已過仍可撤回 / 婉拒；之後排程略過（不再被查出）")
        void cancelBeforeCron() throws Exception {
            UUID orderId = book(today().plusDays(1));
            advance(Duration.ofDays(2));
            mvc.perform(postJson("/api/orders/{id}/decline", sitterToken(), key(), Map.of("reason", "晚了"), orderId)).andExpect(status().isOk());
            counts(cron("orders/review-expiry"), 0, 0, 0);
            assertThat(jdbc.queryForObject("SELECT cancelled_by FROM orders WHERE id = ?", String.class, orderId)).isEqualTo("SITTER");
        }
    }

    @Nested
    @DisplayName("PaymentCronIntegrationTest")
    class PaymentCrons {

        @Test
        @DisplayName("PRD-007 AC-3.2 C2 付款提醒：remind_at 到 → reminded_at + 不可關閉通知；重跑不重發；SUBMITTED / 緊急單不發")
        void reminder() throws Exception {
            UUID orderId = book(today().plusDays(10));
            UUID pay = quote(orderId);
            UUID urgent = book(today().plusDays(1));
            quote(urgent);
            OffsetDateTime remindAt = jdbc.queryForObject("SELECT remind_at FROM payments WHERE id = ?", OffsetDateTime.class, pay);
            clock.advance(Duration.between(OffsetDateTime.now(clock), remindAt));
            counts(cron("payments/reminder"), 1, 0, 0);
            assertThat(jdbc.queryForObject("SELECT reminded_at FROM payments WHERE id = ?", OffsetDateTime.class, pay)).isNotNull();
            assertThat(count("SELECT count(*) FROM notifications WHERE related_order_id = ? AND type = 'PAYMENT_REMINDER' AND mandatory", orderId)).isEqualTo(1);
            counts(cron("payments/reminder"), 0, 0, 0);
            assertThat(count("SELECT count(*) FROM notifications WHERE related_order_id = ? AND type = 'PAYMENT_REMINDER'", urgent)).isZero();
            assertThat(count("SELECT count(*) FROM order_logs WHERE order_id = ? AND action_type LIKE '%REMIND%'", orderId)).isZero();
        }

        @Test
        @DisplayName("PRD-007 AC-3.3 C3 逾期取消 O8：UNPAID 且 now ≥ due_at → CANCELLED（PAYMENT_EXPIRED）、payment VOID、行程取消、雙方不可關閉通知；AC-3.4 SUBMITTED 不逾期")
        void initialExpiry() throws Exception {
            UUID orderId = book(today().plusDays(10));
            UUID pay = quote(orderId);
            UUID submitted = book(today().plusDays(10));
            UUID pay2 = quote(submitted);
            submitProof(submitted, pay2, ownerToken());
            OffsetDateTime due = jdbc.queryForObject("SELECT due_at FROM payments WHERE id = ?", OffsetDateTime.class, pay);
            clock.advance(Duration.between(OffsetDateTime.now(clock), due));
            counts(cron("payments/initial-expiry"), 1, 0, 0);
            assertThat(jdbc.queryForObject("SELECT cancel_reason_code FROM orders WHERE id = ?", String.class, orderId)).isEqualTo("PAYMENT_EXPIRED");
            assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, pay)).isEqualTo("VOID");
            assertThat(count("SELECT count(*) FROM visits WHERE order_id = ? AND status = 'CANCELLED'", orderId)).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM notifications WHERE related_order_id = ? AND type = 'PAYMENT_EXPIRED' AND mandatory", orderId)).isEqualTo(2);
            assertThat(orderStatus(submitted)).isEqualTo("PENDING_PAYMENT");
            // 逾期後駁回 → 下一輪逾期
            UUID sub = jdbc.queryForObject("SELECT id FROM payment_submissions WHERE payment_id = ?", UUID.class, pay2);
            mvc.perform(postJson("/api/orders/{o}/payments/{p}/reject", sitterToken(), key(), Map.of("submissionId", sub.toString(), "reason", "x"),
                    submitted, pay2)).andExpect(status().isOk());
            counts(cron("payments/initial-expiry"), 1, 0, 0);
            assertThat(orderStatus(submitted)).isEqualTo("CANCELLED");
        }

        @Test
        @DisplayName("PRD-007 AC-3.5 C4 待核對升級：建 INITIAL_PAYMENT_UNVERIFIED 待辦、escalated_at、PAYMENT_ESCALATED、雙方通知；重跑不重建；保母先確認則不建")
        void escalation() throws Exception {
            UUID orderId = book(today().plusDays(3));
            UUID pay = quote(orderId);
            submitProof(orderId, pay, ownerToken());
            UUID other = book(today().plusDays(3));
            UUID pay2 = quote(other);
            UUID sub2 = submitProof(other, pay2, ownerToken());
            verify(other, pay2, sub2, sitterToken());
            OffsetDateTime esc = jdbc.queryForObject("SELECT escalate_at FROM payments WHERE id = ?", OffsetDateTime.class, pay);
            clock.advance(Duration.between(OffsetDateTime.now(clock), esc));
            counts(cron("payments/escalation"), 1, 0, 0);
            assertThat(count("SELECT count(*) FROM admin_tasks WHERE order_id = ? AND status = 'OPEN' AND type = 'INITIAL_PAYMENT_UNVERIFIED'", orderId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT escalated_at FROM payments WHERE id = ?", OffsetDateTime.class, pay)).isNotNull();
            counts(cron("payments/escalation"), 0, 0, 0);
            assertThat(count("SELECT count(*) FROM admin_tasks WHERE order_id = ?", other)).isZero();
        }
    }

    @Nested
    @DisplayName("ModificationCronIntegrationTest")
    class ModificationExpiry {

        @Test
        @DisplayName("PRD-016 AC-3.3 C5：REQUESTED / QUOTED 到期 → EXPIRED、ended_at、SYSTEM log、雙方通知；同批一筆例外計 failed 其他照常；DISPUTED 略過")
        void expiry() throws Exception {
            UUID a = confirmedOrder(today().plusDays(5));
            UUID b = confirmedOrder(today().plusDays(5));
            UUID c = confirmedOrder(today().plusDays(5));
            for (UUID o : List.of(a, b, c)) {
                modify(o, ownerToken(), Map.of("type", "CANCEL_ALL", "reason", "x")).andExpect(status().isCreated());
            }
            advance(Duration.ofHours(72));
            doAnswer(inv -> {
                throw new IllegalStateException("boom");
            }).when(processor).modificationExpiry(eq(c));
            counts(cron("modifications/expiry"), 2, 0, 1);
            assertThat(count("SELECT count(*) FROM modification_requests WHERE order_id IN (?, ?) AND status = 'EXPIRED' AND ended_at IS NOT NULL", a, b)).isEqualTo(2);
            assertThat(count("SELECT count(*) FROM order_logs WHERE action_type = 'MODIFICATION_EXPIRED' AND operator_role = 'SYSTEM'")).isEqualTo(2);
            assertThat(count("SELECT count(*) FROM notifications WHERE type = 'MODIFICATION_EXPIRED'")).isEqualTo(4);
            org.mockito.Mockito.reset(processor);
            counts(cron("modifications/expiry"), 1, 0, 0);
            counts(cron("modifications/expiry"), 0, 0, 0);
        }
    }

    @Nested
    @DisplayName("SupplementExpiryIntegrationTest")
    class SupplementExpiry {

        /** CONFIRMED 3 天；變更：減少 1 天、新增 2 天（差額 +500）→ AWAITING_SUPPLEMENT。回傳 [orderId, modId, supplementPaymentId]。 */
        private UUID[] supplementPending(int removeCount, int addCount) throws Exception {
            LocalDate d = today().plusDays(5);
            UUID orderId = confirmedOrder(d, d.plusDays(1), d.plusDays(2));
            List<UUID> vs = visitIds(orderId, "PENDING");
            List<String> remove = vs.subList(0, removeCount).stream().map(UUID::toString).toList();
            List<Map<String, Object>> adds = new java.util.ArrayList<>();
            for (int i = 0; i < addCount; i++) {
                adds.add(addItem(d.plusDays(10 + i), planA, 1, petA));
            }
            UUID mod = modificationId(modify(orderId, sitterToken(), Map.of("type", "CHANGE", "reason", "x", "removeVisitIds", remove, "addItems", adds)));
            JsonNode r = body(mvc.perform(postJson("/api/orders/{o}/modifications/{m}/accept", ownerToken(), key(), Map.of(), orderId, mod)));
            return new UUID[]{orderId, mod, UUID.fromString(r.get("payment").get("paymentId").asString())};
        }

        @Test
        @DisplayName("PRD-023 AC-2.4 補款逾時：payment VOID、變更 EXPIRED、total 扣本次減少目標、已收淨額 > 新總額 → SYSTEM 退款變更（T9 欄位逐一）與退款 payment；E22 撤回 SYSTEM 變更 → 403（AC-3.6）")
        void expiryWithSystemRefund() throws Exception {
            UUID[] ids = supplementPending(1, 2);
            advance(Duration.ofHours(72));
            counts(cron("modifications/supplement-expiry"), 1, 0, 0);
            assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, ids[2])).isEqualTo("VOID");
            assertThat(jdbc.queryForObject("SELECT status FROM modification_requests WHERE id = ?", String.class, ids[1])).isEqualTo("EXPIRED");
            assertThat(jdbc.queryForObject("SELECT total_amount FROM orders WHERE id = ?", Integer.class, ids[0])).isEqualTo(1000);
            Map<String, Object> sys = jdbc.queryForMap("SELECT * FROM modification_requests WHERE source_modification_id = ?", ids[1]);
            assertThat(sys).containsEntry("type", "CHANGE").containsEntry("initiated_by", "SYSTEM").containsEntry("reason", "補款逾期自動退款")
                    .containsEntry("status", "AWAITING_REFUND").containsEntry("new_total_amount", 1000).containsEntry("system_amount", 1000)
                    .containsEntry("quoted_net_paid", 1500).containsEntry("agreed_net_paid", 1500)
                    .containsEntry("quoted_diff", -500).containsEntry("agreed_diff", -500).containsEntry("surcharge_amount", 0)
                    .containsEntry("discount_amount", 0);
            assertThat(sys.get("initiated_by_user_id")).isNull();
            assertThat(sys.get("expires_at")).isNull();
            UUID sysId = (UUID) sys.get("id");
            assertThat(jdbc.queryForObject("SELECT amount FROM payments WHERE modification_request_id = ?", Integer.class, sysId)).isEqualTo(500);
            assertThat(jdbc.queryForObject("SELECT (payload->>'totalAmountAfter')::int FROM order_logs WHERE order_id = ? AND action_type = 'SUPPLEMENT_EXPIRED'",
                    Integer.class, ids[0])).isEqualTo(1000);
            assertThat(count("SELECT count(*) FROM notifications WHERE related_order_id = ? AND type = 'SYSTEM_REFUND_REQUIRED'", ids[0])).isEqualTo(1);
            mvc.perform(postJson("/api/orders/{o}/modifications/{m}/withdraw", ownerToken(), key(), Map.of(), ids[0], sysId))
                    .andExpect(status().isForbidden());
            // 報價明細：SUPPLEMENT_EXPIRED 列
            JsonNode quote = body(mvc.perform(getAs("/api/orders/{o}", ownerToken(), ids[0]))).get("quote");
            assertThat(quote.get("adjustments").get(0).get("kind").asString()).isEqualTo("SUPPLEMENT_EXPIRED");
            assertThat(quote.get("adjustments").get(0).get("amountChange").asInt()).isEqualTo(-500);
        }

        @Test
        @DisplayName("PRD-023 AC-2.8 先前已有 SETTLED 變更時只扣本次取消：A 減 V2 退 500 結清（total 1000）；B 減 V3 加 2 次補 500 逾時 → total 500、SYSTEM 退款新總額 500、退款 500；V1 仍 PENDING、訂單 CONFIRMED")
        void expiryDeductsOnlyThisModification() throws Exception {
            LocalDate d = today().plusDays(5);
            UUID orderId = confirmedOrder(d, d.plusDays(1), d.plusDays(2));
            List<UUID> vs = visitIds(orderId, "PENDING");
            UUID v1 = vs.get(0);
            UUID v2 = vs.get(1);
            UUID v3 = vs.get(2);
            // 變更 A：減少 V2，新總額 1000，退款 500 確認 → SETTLED
            UUID modA = modificationId(modify(orderId, sitterToken(), Map.of("type", "CHANGE", "reason", "a",
                    "removeVisitIds", List.of(v2.toString()), "addItems", List.of())));
            JsonNode ra = body(mvc.perform(postJson("/api/orders/{o}/modifications/{m}/accept", ownerToken(), key(), Map.of(), orderId, modA)));
            UUID refundA = UUID.fromString(ra.get("payment").get("paymentId").asString());
            UUID subA = submitProof(orderId, refundA, sitterToken());
            verify(orderId, refundA, subA, ownerToken()).andExpect(status().isOk());
            assertThat(jdbc.queryForObject("SELECT status FROM modification_requests WHERE id = ?", String.class, modA)).isEqualTo("SETTLED");
            assertThat(jdbc.queryForObject("SELECT total_amount FROM orders WHERE id = ?", Integer.class, orderId)).isEqualTo(1000);
            // 變更 B：減少 V3、新增 2 次，新總額 1500，同意 → V3 CANCELLED、補款 500
            UUID modB = modificationId(modify(orderId, sitterToken(), Map.of("type", "CHANGE", "reason", "b",
                    "removeVisitIds", List.of(v3.toString()),
                    "addItems", List.of(addItem(d.plusDays(10), planA, 1, petA), addItem(d.plusDays(11), planA, 1, petA)))));
            JsonNode rb = body(mvc.perform(postJson("/api/orders/{o}/modifications/{m}/accept", ownerToken(), key(), Map.of(), orderId, modB)));
            UUID supplement = UUID.fromString(rb.get("payment").get("paymentId").asString());
            assertThat(jdbc.queryForObject("SELECT amount FROM payments WHERE id = ?", Integer.class, supplement)).isEqualTo(500);
            assertThat(jdbc.queryForObject("SELECT status FROM visits WHERE id = ?", String.class, v3)).isEqualTo("CANCELLED");
            // B 的補款 UNPAID 逾時
            advance(Duration.ofHours(72));
            counts(cron("modifications/supplement-expiry"), 1, 0, 0);
            assertThat(jdbc.queryForObject("SELECT status FROM modification_requests WHERE id = ?", String.class, modB)).isEqualTo("EXPIRED");
            assertThat(jdbc.queryForObject("SELECT total_amount FROM orders WHERE id = ?", Integer.class, orderId)).isEqualTo(500);
            Map<String, Object> sys = jdbc.queryForMap("SELECT * FROM modification_requests WHERE source_modification_id = ?", modB);
            assertThat(sys).containsEntry("initiated_by", "SYSTEM").containsEntry("new_total_amount", 500)
                    .containsEntry("status", "AWAITING_REFUND");
            assertThat(jdbc.queryForObject("SELECT amount FROM payments WHERE modification_request_id = ?", Integer.class, sys.get("id"))).isEqualTo(500);
            assertThat(jdbc.queryForObject("SELECT status FROM visits WHERE id = ?", String.class, v1)).isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("SELECT status FROM visits WHERE id = ?", String.class, v2)).isEqualTo("CANCELLED");
            assertThat(orderStatus(orderId)).isEqualTo("CONFIRMED");
        }

        @Test
        @DisplayName("PRD-023 AC-2.5 純新增逾時不退款、總額不變；AC-2.7 SUBMITTED 不逾時；C6 vs 保母確認：確認先 → C6 略過")
        void pureAddAndSubmitted() throws Exception {
            UUID[] ids = supplementPending(0, 1);
            UUID[] submitted = supplementPending(0, 1);
            UUID sub = submitProof(submitted[0], submitted[2], ownerToken());
            advance(Duration.ofHours(72));
            counts(cron("modifications/supplement-expiry"), 1, 0, 0);
            assertThat(jdbc.queryForObject("SELECT total_amount FROM orders WHERE id = ?", Integer.class, ids[0])).isEqualTo(1500);
            assertThat(count("SELECT count(*) FROM modification_requests WHERE source_modification_id = ?", ids[1])).isZero();
            assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, submitted[2])).isEqualTo("SUBMITTED");
            verify(submitted[0], submitted[2], sub, sitterToken()).andExpect(status().isOk());
        }

        @Test
        @DisplayName("PRD-023 AC-2.6 重算下限 0；C6 先 → 保母確認得 409 PAYMENT_STATE_CONFLICT（EX-9）")
        void floorAndRaceAfter() throws Exception {
            UUID[] ids = supplementPending(1, 2);
            jdbc.update("UPDATE orders SET total_amount = 100 WHERE id = ?", ids[0]);
            advance(Duration.ofHours(72));
            counts(cron("modifications/supplement-expiry"), 1, 0, 0);
            assertThat(jdbc.queryForObject("SELECT total_amount FROM orders WHERE id = ?", Integer.class, ids[0])).isZero();
            upload(ids[0], ids[2], ownerToken(), key(), PNG).andExpect(status().isConflict());
        }
    }

    @Nested
    @DisplayName("ZombieAndCompletionBasisIntegrationTest")
    class ZombieAndAutoComplete {

        @Test
        @DisplayName("PRD-009 AC-2.1 / PRD-024 AC-2.7 C7 殭屍關閉 → O9（ORDER_STARTED SYSTEM）、VISITS_CLOSED_BY_SYSTEM、基準 = 關閉時間；C8 於 basis + 48h 自動結案、通知雙方；重跑不重做")
        void zombieThenAutoComplete() throws Exception {
            UUID orderId = confirmedOrder(today().plusDays(1));
            advance(Duration.ofDays(5));
            counts(cron("visits/zombie"), 1, 0, 0);
            assertThat(orderStatus(orderId)).isEqualTo("IN_PROGRESS");
            assertThat(logActions(orderId)).contains("ORDER_STARTED", "VISITS_CLOSED_BY_SYSTEM");
            OffsetDateTime basis = jdbc.queryForObject("SELECT completion_basis_at FROM orders WHERE id = ?", OffsetDateTime.class, orderId);
            assertThat(basis).isNotNull();
            counts(cron("orders/auto-complete"), 0, 0, 0);
            clock.advance(Duration.between(OffsetDateTime.now(clock), basis.plusHours(48)));
            counts(cron("orders/auto-complete"), 1, 0, 0);
            assertThat(jdbc.queryForObject("SELECT completed_by FROM orders WHERE id = ?", String.class, orderId)).isEqualTo("SYSTEM");
            assertThat(count("SELECT count(*) FROM notifications WHERE related_order_id = ? AND type = 'ORDER_AUTO_COMPLETED'", orderId)).isEqualTo(2);
            counts(cron("orders/auto-complete"), 0, 0, 0);
        }

        @Test
        @DisplayName("PRD-009 AC-2.2 / AC-2.3 自動結案不看日誌；有未結束變更不被查出（PRD-024 AC-2.6）；DONE 基準 = completed_at + 24h")
        void autoCompleteRules() throws Exception {
            UUID orderId = confirmedOrder(today().plusDays(1), today().plusDays(2));
            List<UUID> vs = visitIds(orderId, "PENDING");
            finishVisit(vs.get(0), false);
            assertThat(jdbc.queryForObject("SELECT completion_basis_at FROM orders WHERE id = ?", OffsetDateTime.class, orderId)).isNull();
            finishVisit(vs.get(1), false);
            OffsetDateTime completedAt = jdbc.queryForObject("SELECT max(completed_at) FROM visits WHERE order_id = ?", OffsetDateTime.class, orderId);
            OffsetDateTime basis = jdbc.queryForObject("SELECT completion_basis_at FROM orders WHERE id = ?", OffsetDateTime.class, orderId);
            assertThat(Duration.between(completedAt, basis).toHours()).isEqualTo(24);
            jdbc.update("INSERT INTO modification_requests (id, order_id, status, type, initiated_by, initiated_by_user_id, reason, expires_at) "
                    + "VALUES (?, ?, 'REQUESTED', 'CANCEL_ALL', 'OWNER', ?, 'x', now() + interval '10 days')", UUID.randomUUID(), orderId, owner);
            clock.advance(Duration.between(OffsetDateTime.now(clock), basis.plusHours(48)));
            counts(cron("orders/auto-complete"), 0, 0, 0);
            jdbc.update("DELETE FROM modification_requests WHERE order_id = ?", orderId);
            counts(cron("orders/auto-complete"), 1, 0, 0);
        }

        @Test
        @DisplayName("§6.3 orderId 只處理該測試帳號訂單；自動觸發跳過測試帳號訂單")
        void testAccountScope() throws Exception {
            jdbc.update("UPDATE users SET email = ? WHERE id = ?", "journey-o-" + UUID.randomUUID() + "@e2e-journey.test", owner);
            UUID testOrder = book(today().plusDays(1));
            advance(Duration.ofDays(3));
            counts(cron("orders/review-expiry"), 0, 0, 0);
            JsonNode r = body(cronRaw("orders/review-expiry", "orderId=" + testOrder).andExpect(status().isOk()));
            counts(r, 1, 0, 0);
        }
    }

    @Nested
    @DisplayName("CleanupJobsIntegrationTest")
    class Cleanups {

        @Test
        @DisplayName("C9 清除過期 idempotency_records 與舊表 idempotency_keys（> 24h）；未過期保留")
        void idempotencyCleanup() throws Exception {
            book(today().plusDays(3));
            jdbc.update("INSERT INTO idempotency_keys (idempotency_key, user_id, created_at) VALUES ('old', ?, now() - interval '2 days')", owner);
            jdbc.update("INSERT INTO idempotency_keys (idempotency_key, user_id) VALUES ('new', ?)", owner);
            assertThat(count("SELECT count(*) FROM idempotency_records")).isEqualTo(1);
            counts(cron("idempotency/cleanup"), 1, 0, 0);
            assertThat(count("SELECT count(*) FROM idempotency_keys")).isEqualTo(1);
            advance(Duration.ofHours(25));
            counts(cron("idempotency/cleanup"), 2, 0, 0);
            assertThat(count("SELECT count(*) FROM idempotency_records")).isZero();
            assertThat(count("SELECT count(*) FROM idempotency_keys")).isZero();
        }

        @Test
        @DisplayName("OrphanProofSweepIntegrationTest C10：只刪無引用、> 24h、非 PROCESSING 的物件")
        void orphanSweep() throws Exception {
            LocalDate day = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC).toLocalDate().minusDays(2);
            String prefix = "payment-proofs/" + day + "/";
            UUID processingRecord = UUID.randomUUID();
            jdbc.update("INSERT INTO idempotency_records (id, user_id, scope, idempotency_key, request_hash, status, claim_token, locked_until, expires_at) "
                    + "VALUES (?, ?, 's', 'k', ?, 'PROCESSING', ?, now() + interval '1 day', now() + interval '1 day')",
                    processingRecord, owner, "a".repeat(64), UUID.randomUUID());
            OffsetDateTime old = OffsetDateTime.now(clock).minusDays(2);
            String orphan = prefix + "o/p/" + UUID.randomUUID() + ".png";
            String processing = prefix + "o/p/" + processingRecord + ".png";
            String fresh = prefix + "o/p/" + UUID.randomUUID() + ".png";
            doReturn(List.of(new MediaStorageService.StoredObject(orphan, old), new MediaStorageService.StoredObject(processing, old),
                    new MediaStorageService.StoredObject(fresh, OffsetDateTime.now(clock))))
                    .when(storage).listObjects(eq(prefix));
            doReturn(true).when(storage).deleteObject(anyString());
            counts(cron("storage/payment-proof-orphans"), 1, 2, 0);
            org.mockito.Mockito.verify(storage).deleteObject(orphan);
            org.mockito.Mockito.verify(storage, never()).deleteObject(processing);
            org.mockito.Mockito.verify(storage, never()).deleteObject(fresh);
        }

        @Test
        @DisplayName("C11 Email 投遞：PENDING → SENT（Idempotency-Key = notification.id）；4xx → FAILED；5xx 退避重試；測試收件者 → SUPPRESSED_TEST 不呼叫 Resend")
        void emailDispatch() throws Exception {
            UUID orderId = book(today().plusDays(3));
            UUID n = jdbc.queryForObject("SELECT id FROM notifications WHERE related_order_id = ? AND email_status = 'PENDING'", UUID.class, orderId);
            doReturn(500).when(emailService).deliver(anyString(), anyString(), anyString(), eq(n.toString()));
            JsonNode r = cron("notifications/email-dispatch");
            assertThat(r.get("failed").asInt()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT email_status FROM notifications WHERE id = ?", String.class, n)).isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("SELECT email_attempts FROM notifications WHERE id = ?", Integer.class, n)).isEqualTo(1);
            advance(Duration.ofMinutes(3));
            doReturn(202).when(emailService).deliver(anyString(), anyString(), anyString(), eq(n.toString()));
            cron("notifications/email-dispatch");
            assertThat(jdbc.queryForObject("SELECT email_status FROM notifications WHERE id = ?", String.class, n)).isEqualTo("SENT");
            UUID other = book(today().plusDays(4));
            UUID n2 = jdbc.queryForObject("SELECT id FROM notifications WHERE related_order_id = ? AND email_status = 'PENDING'", UUID.class, other);
            doReturn(422).when(emailService).deliver(anyString(), anyString(), anyString(), eq(n2.toString()));
            cron("notifications/email-dispatch");
            assertThat(jdbc.queryForObject("SELECT email_status FROM notifications WHERE id = ?", String.class, n2)).isEqualTo("FAILED");
            jdbc.update("UPDATE users SET email = ? WHERE id = ?", "journey-s-" + UUID.randomUUID() + "@e2e-journey.test", sitter);
            UUID testOrder = book(today().plusDays(5));
            org.mockito.Mockito.clearInvocations(emailService);
            body(cronRaw("notifications/email-dispatch", "orderId=" + testOrder).andExpect(status().isOk()));
            assertThat(jdbc.queryForObject("SELECT email_status FROM notifications WHERE related_order_id = ?", String.class, testOrder))
                    .isEqualTo("SUPPRESSED_TEST");
            org.mockito.Mockito.verify(emailService, never()).deliver(anyString(), anyString(), anyString(), anyString());
        }
    }
}
