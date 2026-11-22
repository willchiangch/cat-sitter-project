package com.petsitter.order;

import com.petsitter.application.exception.StorageUnavailableException;
import com.petsitter.application.service.MediaStorageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 付款子流程 E10–E15（SD-ORDER §10.3 PaymentQuery / PaymentSubmission / PaymentSubmissionIdempotency /
 * PaymentSubmissionConcurrency / PaymentReview，合併為 nested 類共用同一 context 與 GCS 替身）。
 */
@DisplayName("E10–E15 PaymentIntegrationTest")
class PaymentIntegrationTest extends OrderIntegrationTestBase {

    @MockitoSpyBean
    private MediaStorageService storage;

    private UUID quotedOrder() throws Exception {
        UUID orderId = book(today().plusDays(6));
        quote(orderId);
        return orderId;
    }

    private ResultActions reject(UUID orderId, UUID paymentId, UUID sub, String token, String reason) throws Exception {
        return mvc.perform(postJson("/api/orders/{o}/payments/{p}/reject", token, key(),
                Map.of("submissionId", sub.toString(), "reason", reason), orderId, paymentId));
    }

    private ResultActions uploadFile(UUID orderId, UUID paymentId, String name, byte[] bytes) throws Exception {
        return mvc.perform(multipart("/api/orders/{o}/payments/{p}/submissions", orderId, paymentId)
                .file(new MockMultipartFile("file", name, "image/png", bytes))
                .param("disclaimerAgreed", "true").param("lastFive", "54321")
                .header("Authorization", ownerToken()).header("Idempotency-Key", key()));
    }

    @Nested
    @DisplayName("PaymentQueryIntegrationTest")
    class Query {

        @Test
        @DisplayName("PRD-007 AC-1.1 CHARGE 付款頁：amount、dueAt、帳號快照（付款方 / 收款方可讀），不含訂單總額欄")
        void chargeSnapshot() throws Exception {
            UUID orderId = quotedOrder();
            JsonNode list = body(mvc.perform(getAs("/api/orders/{o}/payments", ownerToken(), orderId)).andExpect(status().isOk()));
            JsonNode p = list.get(0);
            assertThat(p.get("amount").asInt()).isEqualTo(500);
            assertThat(p.get("accountSnapshot").get("accountNumber").asString()).isEqualTo("1234567890123");
            assertThat(p.get("allowedActions").get(0).asString()).isEqualTo("UPLOAD");
            assertThat(p.has("totalAmount")).isFalse();
            JsonNode asSitter = body(mvc.perform(getAs("/api/orders/{o}/payments", sitterToken(), orderId)));
            assertThat(asSitter.get(0).get("accountSnapshot")).isNotNull();
            // E2 摘要不含帳號快照
            JsonNode detail = body(mvc.perform(getAs("/api/orders/{o}", ownerToken(), orderId)));
            assertThat(detail.get("payments").get(0).has("accountSnapshot")).isFalse();
        }

        @Test
        @DisplayName("PRD-007 AC-2.7 憑證檢視：E12 歷史（submitted_at 倒序、駁回保留）與 E13 簽名網址 TTL 5 分鐘；冪等回放 body 不含簽名網址")
        void submissionsAndProofUrl() throws Exception {
            UUID orderId = quotedOrder();
            UUID paymentId = initialPayment(orderId);
            UUID s1 = submitProof(orderId, paymentId, ownerToken());
            reject(orderId, paymentId, s1, sitterToken(), "看不清楚").andExpect(status().isOk());
            advance(Duration.ofMinutes(1));
            UUID s2 = submitProof(orderId, paymentId, ownerToken());
            JsonNode hist = body(mvc.perform(getAs("/api/orders/{o}/payments/{p}/submissions", sitterToken(), orderId, paymentId)));
            assertThat(hist.get(0).get("submissionId").asString()).isEqualTo(s2.toString());
            assertThat(hist.get(1).get("status").asString()).isEqualTo("REJECTED");
            assertThat(hist.get(1).get("rejectReason").asString()).isEqualTo("看不清楚");
            JsonNode url = body(mvc.perform(getAs("/api/orders/{o}/payments/{p}/submissions/{s}/proof-url", ownerToken(), orderId, paymentId, s2))
                    .andExpect(status().isOk()));
            OffsetDateTime exp = OffsetDateTime.parse(url.get("expiresAt").asString());
            assertThat(Duration.between(OffsetDateTime.now(clock), exp).toSeconds()).isBetween(290L, 301L);
            assertThat(count("SELECT count(*) FROM idempotency_records WHERE response_body::text LIKE '%http%'")).isZero();
            doThrow(new RuntimeException("sign")).when(storage).generateSignedUrl(anyString(), any());
            mvc.perform(getAs("/api/orders/{o}/payments/{p}/submissions/{s}/proof-url", ownerToken(), orderId, paymentId, s2))
                    .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("STORAGE_UNAVAILABLE"));
            reset(storage);
        }

        @Test
        @DisplayName("PRD-007 AC-1.3 讀取越權：他人 403、ADMIN 不在可讀範圍 403；有待辦後 ADMIN 可讀；payment 不屬路徑訂單 404")
        void access() throws Exception {
            UUID orderId = quotedOrder();
            UUID other = quotedOrder();
            UUID paymentId = initialPayment(orderId);
            mvc.perform(getAs("/api/orders/{o}/payments", bearer(stranger, "OWNER"), orderId)).andExpect(status().isForbidden());
            mvc.perform(getAs("/api/orders/{o}/payments", bearer(admin, "ADMIN"), orderId))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
            jdbc.update("INSERT INTO admin_tasks (id, type, order_id, payment_id, status, opened_by_role) VALUES (?, 'INITIAL_PAYMENT_UNVERIFIED', ?, ?, 'OPEN', 'SYSTEM')",
                    UUID.randomUUID(), orderId, paymentId);
            JsonNode adminView = body(mvc.perform(getAs("/api/orders/{o}/payments", bearer(admin, "ADMIN"), orderId)).andExpect(status().isOk()));
            assertThat(adminView.get(0).get("accountSnapshot")).isNotNull();
            mvc.perform(getAs("/api/orders/{o}/payments/{p}/submissions", ownerToken(), other, paymentId)).andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("PaymentSubmissionIntegrationTest")
    class Submission {

        @Test
        @DisplayName("PRD-007 AC-1.4 上傳成功：submission、payment SUBMITTED、PAYMENT_PROOF_SUBMITTED、收款方通知、私有物件鍵含 recordId")
        void success() throws Exception {
            UUID orderId = quotedOrder();
            UUID paymentId = initialPayment(orderId);
            upload(orderId, paymentId, ownerToken(), key(), PNG).andExpect(status().isCreated())
                    .andExpect(jsonPath("$.paymentStatus").value("SUBMITTED")).andExpect(jsonPath("$.submittedAt").exists());
            Map<String, Object> s = jdbc.queryForMap("SELECT * FROM payment_submissions WHERE payment_id = ?", paymentId);
            assertThat((String) s.get("proof_object_key")).startsWith("payment-proofs/").contains(orderId + "/" + paymentId + "/").endsWith(".png");
            assertThat(s.get("proof_content_type")).isEqualTo("image/png");
            assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, paymentId)).isEqualTo("SUBMITTED");
            assertThat(logActions(orderId)).contains("PAYMENT_PROOF_SUBMITTED");
            assertThat(count("SELECT count(*) FROM notifications WHERE user_id = ? AND type = 'PAYMENT_PROOF_SUBMITTED'", sitter)).isEqualTo(1);
        }

        @Test
        @DisplayName("PRD-007 AC-1.5 欄位與檔案：免責未勾 / 後五碼格式 / 缺檔 → 400；非圖片 magic bytes → 422 TYPE；> 5MB → 422 TOO_LARGE；驗證失敗不上傳 GCS")
        void validation() throws Exception {
            UUID orderId = quotedOrder();
            UUID paymentId = initialPayment(orderId);
            mvc.perform(multipart("/api/orders/{o}/payments/{p}/submissions", orderId, paymentId)
                            .file(new MockMultipartFile("file", "a.png", "image/png", PNG)).param("disclaimerAgreed", "false")
                            .param("lastFive", "12345").header("Authorization", ownerToken()).header("Idempotency-Key", key()))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.payload.field").value("disclaimerAgreed"));
            mvc.perform(multipart("/api/orders/{o}/payments/{p}/submissions", orderId, paymentId)
                            .file(new MockMultipartFile("file", "a.png", "image/png", PNG)).param("disclaimerAgreed", "true")
                            .param("lastFive", "1234a").header("Authorization", ownerToken()).header("Idempotency-Key", key()))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.payload.field").value("lastFive"));
            mvc.perform(multipart("/api/orders/{o}/payments/{p}/submissions", orderId, paymentId).param("disclaimerAgreed", "true")
                            .param("lastFive", "12345").header("Authorization", ownerToken()).header("Idempotency-Key", key()))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.payload.field").value("file"));
            uploadFile(orderId, paymentId, "fake.png", "GIF89a....".getBytes())
                    .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("PROOF_FILE_TYPE_UNSUPPORTED"));
            byte[] big = new byte[5 * 1024 * 1024 + 1];
            System.arraycopy(PNG, 0, big, 0, PNG.length);
            uploadFile(orderId, paymentId, "big.png", big)
                    .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("PROOF_FILE_TOO_LARGE"))
                    .andExpect(jsonPath("$.payload.maxBytes").value(5242880));
            org.mockito.Mockito.verify(storage, never()).uploadPrivateIfAbsent(anyString(), any(), anyString());
            byte[] exact = new byte[5 * 1024 * 1024];
            System.arraycopy(PNG, 0, exact, 0, PNG.length);
            uploadFile(orderId, paymentId, "exact.png", exact).andExpect(status().isCreated());
        }

        @Test
        @DisplayName("PRD-007 AC-1.7 期限以 receivedAt 判斷：receivedAt ≥ due_at → 409 DEADLINE_PASSED（PAYMENT_DUE_AT）；SUBMITTED 再上傳 → PAYMENT_STATE_CONFLICT")
        void deadlineAndState() throws Exception {
            UUID orderId = quotedOrder();
            UUID paymentId = initialPayment(orderId);
            OffsetDateTime due = jdbc.queryForObject("SELECT due_at FROM payments WHERE id = ?", OffsetDateTime.class, paymentId);
            clock.advance(Duration.between(OffsetDateTime.now(clock), due));
            upload(orderId, paymentId, ownerToken(), key(), PNG).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("DEADLINE_PASSED")).andExpect(jsonPath("$.payload.deadline").value("PAYMENT_DUE_AT"));
            clock.reset();
            upload(orderId, paymentId, ownerToken(), key(), PNG).andExpect(status().isCreated());
            upload(orderId, paymentId, ownerToken(), key(), PNG).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PAYMENT_STATE_CONFLICT"));
        }

        @Test
        @DisplayName("PRD-007 AC-1.2 方向：保母上傳 CHARGE → 403 FORBIDDEN；ADMIN_ADJUSTMENT payment → 409 PAYMENT_STATE_CONFLICT（跳過方向檢查）")
        void directionAndAdminAdjustment() throws Exception {
            UUID orderId = quotedOrder();
            UUID paymentId = initialPayment(orderId);
            upload(orderId, paymentId, sitterToken(), key(), PNG).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
            UUID adj = UUID.randomUUID();
            jdbc.update("UPDATE payments SET status = 'VOID', voided_at = now() WHERE id = ?", paymentId);
            jdbc.update("INSERT INTO payments (id, order_id, direction, purpose, method, amount, status, adjustment_basis, verified_at) "
                    + "VALUES (?, ?, 'REFUND', 'ADMIN_ADJUSTMENT', 'ADMIN_ADJUSTMENT', 100, 'VERIFIED', '依據', now())", adj, orderId);
            upload(orderId, adj, ownerToken(), key(), PNG).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PAYMENT_STATE_CONFLICT"));
        }

        @Test
        @DisplayName("PRD-024 AC-2.1 DISPUTED 凍結：上傳 → 409 ORDER_STATE_CONFLICT")
        void disputedFrozen() throws Exception {
            UUID orderId = quotedOrder();
            UUID paymentId = initialPayment(orderId);
            jdbc.update("UPDATE orders SET status = 'DISPUTED', disputed_at = now(), confirmed_at = now() WHERE id = ?", orderId);
            upload(orderId, paymentId, ownerToken(), key(), PNG).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("ORDER_STATE_CONFLICT"));
        }
    }

    @Nested
    @DisplayName("PaymentSubmissionIdempotencyIntegrationTest")
    class SubmissionIdempotency {

        @Test
        @DisplayName("PRD-007 AC-1.6 同 key 同檔重送 → 回放、不重傳 GCS、submission 一筆；同 key 不同檔 → 422；缺 key → 400")
        void replay() throws Exception {
            UUID orderId = quotedOrder();
            UUID paymentId = initialPayment(orderId);
            String k = key();
            String first = upload(orderId, paymentId, ownerToken(), k, PNG).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
            String second = upload(orderId, paymentId, ownerToken(), k, PNG).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
            assertThat(json.readTree(second)).isEqualTo(json.readTree(first));
            org.mockito.Mockito.verify(storage, times(1)).uploadPrivateIfAbsent(anyString(), any(), anyString());
            assertThat(count("SELECT count(*) FROM payment_submissions WHERE payment_id = ?", paymentId)).isEqualTo(1);
            byte[] other = Arrays.copyOf(PNG, PNG.length + 1);
            upload(orderId, paymentId, ownerToken(), k, other).andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
            upload(orderId, paymentId, ownerToken(), null, PNG).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.payload.field").value("Idempotency-Key"));
            reset(storage);
        }

        @Test
        @DisplayName("PRD-007 AC-1.8 GCS 失敗 → 503 STORAGE_UNAVAILABLE、payment 不變、冪等紀錄刪除；同 key 重試成功")
        void storageFailureThenRetry() throws Exception {
            UUID orderId = quotedOrder();
            UUID paymentId = initialPayment(orderId);
            String k = key();
            doThrow(new StorageUnavailableException("down", new RuntimeException())).when(storage)
                    .uploadPrivateIfAbsent(anyString(), any(), anyString());
            upload(orderId, paymentId, ownerToken(), k, PNG).andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("STORAGE_UNAVAILABLE"));
            assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, paymentId)).isEqualTo("UNPAID");
            assertThat(count("SELECT count(*) FROM idempotency_records WHERE idempotency_key = ?", k)).isZero();
            reset(storage);
            upload(orderId, paymentId, ownerToken(), k, PNG).andExpect(status().isCreated());
        }
    }

    @Nested
    @DisplayName("PaymentSubmissionConcurrencyIntegrationTest")
    class SubmissionConcurrency {

        @Test
        @DisplayName("PRD-007 AC-1.8 / PRD-027 AC-3.5 上傳 vs 保母取消兩執行緒：恰一方成功；取消先到時物件被刪、無 submission")
        void uploadVsSitterCancel() throws Exception {
            UUID orderId = quotedOrder();
            UUID paymentId = initialPayment(orderId);
            int[] codes = race(() -> upload(orderId, paymentId, ownerToken(), key(), PNG),
                    () -> mvc.perform(postJson("/api/orders/{id}/sitter-cancel", sitterToken(), key(), Map.of("reason", "x"), orderId)));
            boolean uploadWon = codes[0] == 201;
            assertThat(uploadWon ^ (codes[1] == 200)).isTrue();
            if (uploadWon) {
                assertThat(count("SELECT count(*) FROM payment_submissions WHERE payment_id = ?", paymentId)).isEqualTo(1);
                assertThat(orderStatus(orderId)).isEqualTo("PENDING_PAYMENT");
            } else {
                assertThat(count("SELECT count(*) FROM payment_submissions WHERE payment_id = ?", paymentId)).isZero();
                assertThat(orderStatus(orderId)).isEqualTo("CANCELLED");
            }
        }

        @Test
        @DisplayName("PRD-007 AC-1.8 交易 B 失敗（鎖後重驗 VOID）→ 409，且仍持有 token、無引用 → 刪除已上傳物件")
        void lockRecheckFailureDeletesObject() throws Exception {
            UUID orderId = quotedOrder();
            UUID paymentId = initialPayment(orderId);
            CountingDelete counter = new CountingDelete();
            org.mockito.Mockito.doAnswer(inv -> {
                Object r = inv.callRealMethod();
                // 上傳完成後、交易 B 前，另一方作廢 payment
                jdbc.update("UPDATE payments SET status = 'VOID', voided_at = now() WHERE id = ?", paymentId);
                return r;
            }).when(storage).uploadPrivateIfAbsent(anyString(), any(), anyString());
            org.mockito.Mockito.doAnswer(inv -> {
                counter.n++;
                return inv.callRealMethod();
            }).when(storage).deleteObject(anyString());
            upload(orderId, paymentId, ownerToken(), key(), PNG).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PAYMENT_STATE_CONFLICT"));
            assertThat(counter.n).isEqualTo(1);
            reset(storage);
        }
    }

    static final class CountingDelete {
        int n;
    }

    @Nested
    @DisplayName("PaymentReviewIntegrationTest")
    class Review {

        @Test
        @DisplayName("PRD-007 AC-2.1 首次付款確認 → CONFIRMED + confirmed_at（月額度 +1）、OPEN 待辦 RESOLVED、一筆 PAYMENT_VERIFIED（transitions）、雙方 ORDER_ACTIVATED")
        void verifyInitial() throws Exception {
            UUID orderId = quotedOrder();
            UUID paymentId = initialPayment(orderId);
            UUID sub = submitProof(orderId, paymentId, ownerToken());
            UUID task = UUID.randomUUID();
            jdbc.update("INSERT INTO admin_tasks (id, type, order_id, payment_id, status, opened_by_role) VALUES (?, 'INITIAL_PAYMENT_UNVERIFIED', ?, ?, 'OPEN', 'SYSTEM')",
                    task, orderId, paymentId);
            verify(orderId, paymentId, sub, sitterToken()).andExpect(status().isOk())
                    .andExpect(jsonPath("$.orderStatus").value("CONFIRMED")).andExpect(jsonPath("$.paymentStatus").value("VERIFIED"));
            assertThat(jdbc.queryForObject("SELECT confirmed_at FROM orders WHERE id = ?", OffsetDateTime.class, orderId)).isNotNull();
            assertThat(jdbc.queryForMap("SELECT status, resolution FROM admin_tasks WHERE id = ?", task))
                    .containsEntry("status", "RESOLVED").containsEntry("resolution", "SITTER_VERIFIED");
            assertThat(count("SELECT count(*) FROM order_logs WHERE order_id = ? AND action_type = 'PAYMENT_VERIFIED'", orderId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT jsonb_array_length(payload->'transitions') FROM order_logs WHERE order_id = ? AND action_type = 'PAYMENT_VERIFIED'",
                    Integer.class, orderId)).isEqualTo(4);
            assertThat(count("SELECT count(*) FROM notifications WHERE related_order_id = ? AND type = 'ORDER_ACTIVATED'", orderId)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT status FROM payment_submissions WHERE id = ?", String.class, sub)).isEqualTo("ACCEPTED");
        }

        @Test
        @DisplayName("PRD-007 AC-2.3 駁回：submission REJECTED 保留、payment UNPAID、飼主通知不可關閉、待辦 RESOLVED SITTER_REJECTED；可重新上傳")
        void rejectAndReupload() throws Exception {
            UUID orderId = quotedOrder();
            UUID paymentId = initialPayment(orderId);
            UUID sub = submitProof(orderId, paymentId, ownerToken());
            reject(orderId, paymentId, sub, sitterToken(), "金額不符").andExpect(status().isOk())
                    .andExpect(jsonPath("$.paymentStatus").value("UNPAID")).andExpect(jsonPath("$.submissionStatus").value("REJECTED"));
            assertThat(count("SELECT count(*) FROM notifications WHERE related_order_id = ? AND type = 'PAYMENT_REJECTED' AND mandatory = true", orderId)).isEqualTo(1);
            submitProof(orderId, paymentId, ownerToken());
            assertThat(count("SELECT count(*) FROM payment_submissions WHERE payment_id = ?", paymentId)).isEqualTo(2);
        }

        @Test
        @DisplayName("PRD-007 AC-2.4 / AC-2.5 方向與角色：飼主確認 CHARGE / 駁回 → 403 FORBIDDEN；該單保母以 OWNER token → 403 ROLE_MISMATCH（G4a）；ADMIN → ROLE_MISMATCH")
        void directionAndRole() throws Exception {
            UUID orderId = quotedOrder();
            UUID paymentId = initialPayment(orderId);
            UUID sub = submitProof(orderId, paymentId, ownerToken());
            verify(orderId, paymentId, sub, ownerToken()).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
            reject(orderId, paymentId, sub, ownerToken(), "x").andExpect(status().isForbidden());
            verify(orderId, paymentId, sub, bearer(sitter, "OWNER")).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ROLE_MISMATCH"));
            verify(orderId, paymentId, sub, bearer(admin, "ADMIN")).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ROLE_MISMATCH"));
            verify(orderId, paymentId, sub, bearer(stranger, "OWNER")).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }

        @Test
        @DisplayName("PRD-007 AC-1.2 / §3 權限表 E15 駁回 ADMIN_ADJUSTMENT：飼主 / 保母皆 409 PAYMENT_STATE_CONFLICT（跳過方向檢查，非 403）")
        void rejectAdminAdjustmentConflict() throws Exception {
            UUID orderId = quotedOrder();
            UUID paymentId = initialPayment(orderId);
            jdbc.update("UPDATE payments SET status = 'VOID', voided_at = now() WHERE id = ?", paymentId);
            UUID adj = UUID.randomUUID();
            jdbc.update("INSERT INTO payments (id, order_id, direction, purpose, method, amount, status, adjustment_basis, verified_at) "
                    + "VALUES (?, ?, 'REFUND', 'ADMIN_ADJUSTMENT', 'ADMIN_ADJUSTMENT', 100, 'VERIFIED', '依據', now())", adj, orderId);
            reject(orderId, adj, UUID.randomUUID(), sitterToken(), "x").andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PAYMENT_STATE_CONFLICT"));
            reject(orderId, adj, UUID.randomUUID(), ownerToken(), "x").andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PAYMENT_STATE_CONFLICT"));
            verify(orderId, adj, UUID.randomUUID(), sitterToken()).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PAYMENT_STATE_CONFLICT"));
            // 當事人錯角色仍先得 G4a 403
            reject(orderId, adj, UUID.randomUUID(), bearer(sitter, "OWNER"), "x").andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ROLE_MISMATCH"));
            assertThat(count("SELECT count(*) FROM order_logs WHERE order_id = ? AND action_type = 'PAYMENT_REJECTED'", orderId)).isZero();
        }

        @Test
        @DisplayName("PRD-007 AC-2.6 狀態不允許與過期畫面：submissionId 非目前那筆 → 409 PAYMENT_STATE_CONFLICT；已確認再確認 → 409；同 key 回放")
        void staleAndReplay() throws Exception {
            UUID orderId = quotedOrder();
            UUID paymentId = initialPayment(orderId);
            UUID sub = submitProof(orderId, paymentId, ownerToken());
            verify(orderId, paymentId, UUID.randomUUID(), sitterToken()).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PAYMENT_STATE_CONFLICT"));
            String k = key();
            mvc.perform(postJson("/api/orders/{o}/payments/{p}/verify", sitterToken(), k, Map.of("submissionId", sub.toString()), orderId, paymentId))
                    .andExpect(status().isOk());
            mvc.perform(postJson("/api/orders/{o}/payments/{p}/verify", sitterToken(), k, Map.of("submissionId", sub.toString()), orderId, paymentId))
                    .andExpect(status().isOk());
            verify(orderId, paymentId, sub, sitterToken()).andExpect(status().isConflict());
            assertThat(count("SELECT count(*) FROM order_logs WHERE order_id = ? AND action_type = 'PAYMENT_VERIFIED'", orderId)).isEqualTo(1);
        }

        @Test
        @DisplayName("PRD-007 AC-2.8 Email 偏好關閉不影響轉移（站內必寫）")
        void notificationPreferenceDoesNotBlock() throws Exception {
            jdbc.update("INSERT INTO notification_preferences (id, user_id, category, enable_in_app, enable_email) VALUES (?, ?, 'ORDER_AFFAIR', true, false)",
                    UUID.randomUUID(), owner);
            UUID orderId = quotedOrder();
            UUID paymentId = initialPayment(orderId);
            verify(orderId, paymentId, submitProof(orderId, paymentId, ownerToken()), sitterToken()).andExpect(status().isOk());
            assertThat(count("SELECT count(*) FROM notifications WHERE user_id = ? AND type = 'ORDER_ACTIVATED' AND email_status = 'SKIPPED_PREFERENCE'", owner))
                    .isEqualTo(1);
        }
    }
}
