package com.petsitter.interfaces.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petsitter.application.dto.QuoteRequest;
import com.petsitter.application.service.EvaluationService;
import com.petsitter.domain.model.ModificationRequest;
import com.petsitter.domain.model.Order;
import com.petsitter.domain.model.Subscription;
import com.petsitter.domain.model.User;
import com.petsitter.domain.model.Visit;
import com.petsitter.domain.repository.ModificationRequestRepository;
import com.petsitter.domain.repository.OrderRepository;
import com.petsitter.domain.repository.SubscriptionRepository;
import com.petsitter.domain.repository.UserRepository;
import com.petsitter.domain.repository.VisitRepository;
import com.petsitter.infrastructure.security.TokenContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("local")
@WithMockUser(roles = "SITTER")
@DisplayName("OrderController SaaS Gating AOP 測試")
class OrderControllerTest {

    static {
        System.setProperty("com.github.dockerjava.api.version", "1.44");
        System.setProperty("testcontainers.ryuk.disabled", "true");
    }

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    private ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private VisitRepository visitRepository;

    @Autowired
    private ModificationRequestRepository modRequestRepository;

    @MockitoBean
    private EvaluationService evaluationService;

    @MockitoBean
    private com.petsitter.application.service.MediaStorageService mediaStorageService;

    private static org.springframework.mock.web.MockMultipartFile refundImage() {
        return new org.springframework.mock.web.MockMultipartFile("file", "refund.jpg", MediaType.IMAGE_JPEG_VALUE, "img".getBytes());
    }

    private UUID sitterId;
    private UUID orderId;
    private UUID ownerId;
    private UUID realOrderId;
    private UUID visitId;

    @BeforeEach
    void setUp() {
        modRequestRepository.deleteAll();
        subscriptionRepository.deleteAll();
        orderRepository.deleteAll();
        userRepository.deleteAll();

        User sitter = User.builder().email("sitter@test.com").passwordHash("hash").role("SITTER").build();
        userRepository.save(sitter);
        this.sitterId = sitter.getId();
        this.orderId = UUID.randomUUID();

        User owner = userRepository.save(User.builder().email("owner-orderdetail@test.com").passwordHash("hash").role("OWNER").build());
        this.ownerId = owner.getId();

        Order realOrder = orderRepository.save(Order.builder()
                .sitter(sitter)
                .owner(owner)
                .items(List.of())
                .status("CONFIRMED")
                .planId(UUID.randomUUID())
                .build());
        this.realOrderId = realOrder.getId();

        Visit visit = visitRepository.save(Visit.builder()
                .order(realOrder)
                .status("PENDING")
                .planId(realOrder.getPlanId())
                .snapshotPlanTitle("測試方案")
                .scheduledAt(OffsetDateTime.now())
                .build());
        this.visitId = visit.getId();
    }

    @AfterEach
    void tearDown() {
        TokenContext.clear();
    }

    @Test
    @DisplayName("fix-batch10 H5：POST /{orderId}/cancel 飼主本人撤 PENDING 單 → 200，身分取自 token")
    void should_CancelOrder_When_OwnerCancelsOwnPendingOrder() throws Exception {
        Order order = orderRepository.findById(realOrderId).orElseThrow();
        order.setStatus("PENDING");
        orderRepository.saveAndFlush(order);
        int version = orderRepository.findById(realOrderId).orElseThrow().getVersion();
        TokenContext.setUserId(ownerId);

        mockMvc.perform(post("/api/orders/{orderId}/cancel", realOrderId)
                .with(user(ownerId.toString()).roles("OWNER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + version + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("已取消預約"));

        Order after = orderRepository.findById(realOrderId).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(after.getStatus()).isEqualTo("CANCELLED");
        org.assertj.core.api.Assertions.assertThat(after.getCancelledBy()).isEqualTo("OWNER");

        // 保母端訂單詳情 / 清單看得到 cancelledBy=OWNER（前端據此顯示「飼主已取消預約」）
        TokenContext.setUserId(sitterId);
        mockMvc.perform(get("/api/orders/{orderId}", realOrderId)
                .with(user(sitterId.toString()).roles("SITTER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelledBy").value("OWNER"))
                .andExpect(jsonPath("$.cancellableByOwner").value(false));
        mockMvc.perform(get("/api/orders/sitter")
                .with(user(sitterId.toString()).roles("SITTER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].cancelledBy").value("OWNER"));
    }

    @Test
    @DisplayName("fix-batch10 H5：POST /{orderId}/cancel 保母角色 → 403、別的飼主 → 403、已付款 → 409、缺 version → 400")
    void should_RejectCancel_When_NotOwnerOrNotCancellable() throws Exception {
        Order order = orderRepository.findById(realOrderId).orElseThrow();
        order.setStatus("PENDING");
        orderRepository.saveAndFlush(order);
        int version = orderRepository.findById(realOrderId).orElseThrow().getVersion();
        String body = "{\"version\":" + version + "}";

        TokenContext.setUserId(sitterId);
        mockMvc.perform(post("/api/orders/{orderId}/cancel", realOrderId)
                .with(user(sitterId.toString()).roles("SITTER"))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());

        User stranger = userRepository.save(User.builder().email("stranger-h5@test.com").passwordHash("hash").role("OWNER").build());
        TokenContext.setUserId(stranger.getId());
        mockMvc.perform(post("/api/orders/{orderId}/cancel", realOrderId)
                .with(user(stranger.getId().toString()).roles("OWNER"))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());

        TokenContext.setUserId(ownerId);
        mockMvc.perform(post("/api/orders/{orderId}/cancel", realOrderId)
                .with(user(ownerId.toString()).roles("OWNER"))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());

        org.assertj.core.api.Assertions.assertThat(orderRepository.findById(realOrderId).orElseThrow().getStatus())
                .isEqualTo("PENDING");

        Order paid = orderRepository.findById(realOrderId).orElseThrow();
        paid.setStatus("PAID");
        orderRepository.saveAndFlush(paid);
        int paidVersion = orderRepository.findById(realOrderId).orElseThrow().getVersion();
        mockMvc.perform(post("/api/orders/{orderId}/cancel", realOrderId)
                .with(user(ownerId.toString()).roles("OWNER"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + paidVersion + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("STATE_CONFLICT"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("PRD-016")));
    }

    @Test
    @DisplayName("fix-batch10 H2：POST /{orderId}/complete 對已 COMPLETED 訂單回 409 STATE_CONFLICT，撥款日期不變")
    void should_Return409_And_KeepPayoutAt_When_CompletingCompletedOrder() throws Exception {
        Order order = orderRepository.findById(realOrderId).orElseThrow();
        OffsetDateTime completedAt = OffsetDateTime.now().minusDays(1).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        order.setStatus("COMPLETED");
        order.setCompletedAt(completedAt);
        order.setPayoutAt(completedAt.plusDays(3));
        orderRepository.save(order);
        Visit visit = visitRepository.findById(visitId).orElseThrow();
        visit.setStatus("DONE");
        visitRepository.save(visit);
        Order before = orderRepository.findById(realOrderId).orElseThrow();
        TokenContext.setUserId(ownerId);

        mockMvc.perform(post("/api/orders/{orderId}/complete", realOrderId)
                .with(user(ownerId.toString()).roles("OWNER")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("STATE_CONFLICT"))
                .andExpect(jsonPath("$.message").value("此訂單已結案，不可重複結案"));

        Order after = orderRepository.findById(realOrderId).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(after.getPayoutAt()).isEqualTo(before.getPayoutAt());
        org.assertj.core.api.Assertions.assertThat(after.getCompletedAt()).isEqualTo(before.getCompletedAt());
    }

    @Test
    @DisplayName("FREE 方案保母若嘗試調價 (adjustment != 0)，應被 AOP 攔截並回傳 403")
    void should_BlockAdjustment_For_FreePlan() throws Exception {
        // Given: FREE 方案
        subscriptionRepository.save(Subscription.builder().sitter(userRepository.findById(sitterId).get()).planTier("FREE").build());

        QuoteRequest request = QuoteRequest.builder()
                .adjustmentAmount(100)
                .expectedTotalAmount(600)
                .version(1)
                .build();

        // When & Then（fix-batch11 I1：身分取自 token，PlanGatingAspect 以 TokenContext 判斷方案）
        TokenContext.setUserId(sitterId);
        mockMvc.perform(post("/api/orders/{orderId}/quote", orderId)
                .with(user("test").roles("SITTER"))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden()) 
                .andExpect(jsonPath("$.message").value("當前方案不支援自訂報價 (需要專業方案以上)"));
    }

    @Test
    @DisplayName("PRO 方案保母調價應允許通過")
    void should_AllowAdjustment_For_ProPlan() throws Exception {
        // Given: PRO 方案
        subscriptionRepository.save(Subscription.builder().sitter(userRepository.findById(sitterId).get()).planTier("PRO").build());

        QuoteRequest request = QuoteRequest.builder()
                .adjustmentAmount(100)
                .expectedTotalAmount(600)
                .version(1)
                .build();

        // When & Then（fix-batch11 I1：身分取自 token，PlanGatingAspect 以 TokenContext 判斷方案）
        TokenContext.setUserId(sitterId);
        mockMvc.perform(post("/api/orders/{orderId}/quote", orderId)
                .with(user("test").roles("SITTER"))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /{orderId}/visits：保母查詢自己訂單的行程清單成功")
    void should_ReturnVisits_When_SitterQueriesOwnOrder() throws Exception {
        TokenContext.setUserId(sitterId);

        mockMvc.perform(get("/api/orders/{orderId}/visits", realOrderId)
                .with(user(sitterId.toString()).roles("SITTER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(visitId.toString()))
                .andExpect(jsonPath("$[0].status").value("PENDING"));
    }

    @Test
    @DisplayName("GET /{orderId}/visits：飼主查詢自己訂單的行程清單成功")
    void should_ReturnVisits_When_OwnerQueriesOwnOrder() throws Exception {
        TokenContext.setUserId(ownerId);

        mockMvc.perform(get("/api/orders/{orderId}/visits", realOrderId)
                .with(user(ownerId.toString()).roles("OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(visitId.toString()));
    }

    @Autowired
    private com.petsitter.domain.repository.VisitServiceReportRepository visitServiceReportRepository;

    @Test
    @DisplayName("GET /{orderId}/visits（fix-batch12 U5）：reportSubmitted 只在日誌已正式送出時為 true（草稿、沒有日誌、已刪除皆 false）")
    void should_FlagReportSubmitted_OnlyForSubmittedReports() throws Exception {
        Order order = orderRepository.findById(realOrderId).orElseThrow();
        java.util.function.BiFunction<String, Integer, Visit> visitAt = (status, days) -> visitRepository.save(Visit.builder()
                .order(order).status(status).planId(order.getPlanId()).snapshotPlanTitle("U5")
                .scheduledAt(OffsetDateTime.now().plusDays(days)).build());
        Visit submitted = visitAt.apply("DONE", 1);
        Visit draft = visitAt.apply("DONE", 2);
        Visit deletedSubmitted = visitAt.apply("CLOSED_BY_SYSTEM", 3);
        visitServiceReportRepository.save(com.petsitter.domain.model.VisitServiceReport.builder()
                .visitId(submitted.getId()).status("SUBMITTED").content("已送出")
                .submittedAt(OffsetDateTime.now()).build());
        visitServiceReportRepository.save(com.petsitter.domain.model.VisitServiceReport.builder()
                .visitId(draft.getId()).status("DRAFT").content("草稿").build());
        visitServiceReportRepository.save(com.petsitter.domain.model.VisitServiceReport.builder()
                .visitId(deletedSubmitted.getId()).status("SUBMITTED").content("已刪除")
                .submittedAt(OffsetDateTime.now()).isDeleted(true).build());
        TokenContext.setUserId(ownerId);

        // 依 scheduledAt 排序：setUp 的 PENDING（今天）、submitted、draft、deletedSubmitted
        mockMvc.perform(get("/api/orders/{orderId}/visits", realOrderId)
                .with(user(ownerId.toString()).roles("OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(visitId.toString()))
                .andExpect(jsonPath("$[0].reportSubmitted").value(false))
                .andExpect(jsonPath("$[1].id").value(submitted.getId().toString()))
                .andExpect(jsonPath("$[1].reportSubmitted").value(true))
                .andExpect(jsonPath("$[2].id").value(draft.getId().toString()))
                .andExpect(jsonPath("$[2].reportSubmitted").value(false))
                .andExpect(jsonPath("$[3].id").value(deletedSubmitted.getId().toString()))
                .andExpect(jsonPath("$[3].reportSubmitted").value(false));
    }

    @Test
    @DisplayName("GET /{orderId}/visits：BOLA 防護 — 非訂單雙方查詢應被拒絕")
    void should_Return403_When_UnrelatedUserQueriesVisits() throws Exception {
        User unrelated = userRepository.save(User.builder().email("unrelated-orderdetail@test.com").passwordHash("hash").role("SITTER").build());
        TokenContext.setUserId(unrelated.getId());

        mockMvc.perform(get("/api/orders/{orderId}/visits", realOrderId)
                .with(user(unrelated.getId().toString()).roles("SITTER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("迴歸測試 (BOLA)：退款憑證上傳過去吃前端傳的 sitterId query param，" +
            "飼主角色呼叫應被角色守門擋下 (403)，不該只靠身份比對")
    void should_Return403_When_OwnerCallsRefundProofEndpoint() throws Exception {
        TokenContext.setUserId(ownerId);

        mockMvc.perform(multipart("/api/orders/{orderId}/modification/refund-proof", realOrderId)
                .file(refundImage())
                .with(user(ownerId.toString()).roles("OWNER")))
                .andExpect(status().isForbidden());
        verify(mediaStorageService, never()).uploadRefundProof(any(), any(), any());
    }

    @Test
    @DisplayName("迴歸測試 (BOLA)：確認收到退款過去吃前端傳的 ownerId query param，" +
            "保母角色呼叫應被角色守門擋下 (403)")
    void should_Return403_When_SitterCallsRefundConfirmEndpoint() throws Exception {
        TokenContext.setUserId(sitterId);

        mockMvc.perform(post("/api/orders/{orderId}/modification/refund-confirm", realOrderId)
                .with(user(sitterId.toString()).roles("SITTER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("迴歸測試 (BOLA)：退款憑證上傳身份改由 TokenContext 取得，" +
            "以真實保母身份呼叫應成功寫入，不再依賴前端傳入的 sitterId")
    void should_UploadRefundProof_Successfully_UsingTokenContextIdentity() throws Exception {
        // 退款憑證只能在 REFUND_VERIFY 階段上傳 (C2 狀態防護)，前置狀態補齊
        Order refundOrder = orderRepository.findById(realOrderId).get();
        refundOrder.setStatus("REFUND_VERIFY");
        orderRepository.save(refundOrder);
        ModificationRequest modReq = modRequestRepository.save(ModificationRequest.builder()
                .order(orderRepository.findById(realOrderId).get())
                .status("M_DONE")
                .requestedBy("OWNER")
                .diffAmount(-500)
                .payload(List.of())
                .build());

        TokenContext.setUserId(sitterId);
        when(mediaStorageService.uploadRefundProof(eq(sitterId), eq(realOrderId), any()))
                .thenReturn("refund-proofs/2026-10-24/" + realOrderId + "_abc.jpg");

        mockMvc.perform(multipart("/api/orders/{orderId}/modification/refund-proof", realOrderId)
                .file(refundImage())
                .with(user(sitterId.toString()).roles("SITTER")))
                .andExpect(status().isOk());

        // C4：存的是上傳後的私有 Object Key，不再是保母手填的網址
        var saved = modRequestRepository.findById(modReq.getId()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(saved.getRefundProofUrl())
                .isEqualTo("refund-proofs/2026-10-24/" + realOrderId + "_abc.jpg");
    }

    @Test
    @DisplayName("迴歸測試 (BOLA)：確認收到退款身份改由 TokenContext 取得，" +
            "以真實飼主身份呼叫應成功轉為 CONFIRMED")
    void should_ConfirmRefund_Successfully_UsingTokenContextIdentity() throws Exception {
        Order order = orderRepository.findById(realOrderId).get();
        order.setStatus("REFUND_VERIFY");
        orderRepository.save(order);

        TokenContext.setUserId(ownerId);

        mockMvc.perform(post("/api/orders/{orderId}/modification/refund-confirm", realOrderId)
                .with(user(ownerId.toString()).roles("OWNER")))
                .andExpect(status().isOk());

        var saved = orderRepository.findById(realOrderId).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(saved.getStatus()).isEqualTo("CANCELLED");
    }

    // ===== C2 迴歸：變更協商各端點的狀態防護，已完成的動作重送一律 409，資料不得被改寫 =====

    private ModificationRequest saveModRequest(String orderStatus, String modStatus, int diff) {
        Order o = orderRepository.findById(realOrderId).get();
        o.setStatus(orderStatus);
        orderRepository.save(o);
        return modRequestRepository.save(ModificationRequest.builder()
                .order(orderRepository.findById(realOrderId).get())
                .status(modStatus)
                .requestedBy("OWNER")
                .diffAmount(diff)
                .newTotalAmount(1000 + diff)
                .previousStatus("CONFIRMED")
                .payload(List.of())
                .dates(List.of())
                .build());
    }

    @Test
    @DisplayName("C2 迴歸：已報價 (QUOTED) 後再送報價應 409，報價金額不被覆寫")
    void should_Return409_When_QuoteResubmittedAfterQuoted() throws Exception {
        ModificationRequest modReq = saveModRequest("MODIFYING", "QUOTED", 600);
        TokenContext.setUserId(sitterId);

        mockMvc.perform(post("/api/orders/{orderId}/modification/quote", realOrderId)
                        .with(user(sitterId.toString()).roles("SITTER"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .param("modRequestId", modReq.getId().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newTotalAmount\":9999,\"version\":0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("STATE_CONFLICT"));

        var saved = modRequestRepository.findById(modReq.getId()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(saved.getStatus()).isEqualTo("QUOTED");
        org.assertj.core.api.Assertions.assertThat(saved.getNewTotalAmount()).isEqualTo(1600);
    }

    @Test
    @DisplayName("C2 迴歸：已同意變更 (M_DONE) 後再送同意應 409，訂單狀態不變")
    void should_Return409_When_ConfirmResubmittedAfterDone() throws Exception {
        ModificationRequest modReq = saveModRequest("REFUND_VERIFY", "M_DONE", -500);
        TokenContext.setUserId(ownerId);

        mockMvc.perform(post("/api/orders/{orderId}/modification/confirm", realOrderId)
                        .with(user(ownerId.toString()).roles("OWNER"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .param("modRequestId", modReq.getId().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agreedDiffAmount\":-500,\"version\":0}"))
                .andExpect(status().isConflict());

        org.assertj.core.api.Assertions.assertThat(orderRepository.findById(realOrderId).get().getStatus())
                .isEqualTo("REFUND_VERIFY");
    }

    @Test
    @DisplayName("C2 迴歸：飼主已確認收到退款（訂單已回 CONFIRMED）後，保母再上傳退款憑證應 409，舊憑證不被改寫")
    void should_Return409_When_RefundProofUploadedAfterRefundConfirmed() throws Exception {
        ModificationRequest modReq = saveModRequest("CONFIRMED", "M_DONE", -500);
        modReq.setRefundProofUrl("refund-proofs/2026-10-24/original.jpg");
        modRequestRepository.save(modReq);
        TokenContext.setUserId(sitterId);

        mockMvc.perform(multipart("/api/orders/{orderId}/modification/refund-proof", realOrderId)
                        .file(refundImage())
                        .with(user(sitterId.toString()).roles("SITTER")))
                .andExpect(status().isConflict());
        verify(mediaStorageService, never()).uploadRefundProof(any(), any(), any());

        org.assertj.core.api.Assertions.assertThat(modRequestRepository.findById(modReq.getId()).get().getRefundProofUrl())
                .isEqualTo("refund-proofs/2026-10-24/original.jpg");
    }

    @Test
    @DisplayName("C2 迴歸：補款分支 (PENDING_PAYMENT、差額為正) 不可上傳退款憑證，應 409")
    void should_Return409_When_RefundProofUploadedOnSupplementBranch() throws Exception {
        ModificationRequest modReq = saveModRequest("PENDING_PAYMENT", "M_DONE", 600);
        TokenContext.setUserId(sitterId);

        mockMvc.perform(multipart("/api/orders/{orderId}/modification/refund-proof", realOrderId)
                        .file(refundImage())
                        .with(user(sitterId.toString()).roles("SITTER")))
                .andExpect(status().isConflict());
        verify(mediaStorageService, never()).uploadRefundProof(any(), any(), any());

        org.assertj.core.api.Assertions.assertThat(modRequestRepository.findById(modReq.getId()).get().getRefundProofUrl())
                .isNull();
    }

    @Test
    @DisplayName("C2 迴歸：已確認收到退款後重送確認應 409")
    void should_Return409_When_RefundConfirmResubmitted() throws Exception {
        saveModRequest("CONFIRMED", "M_DONE", -500);
        TokenContext.setUserId(ownerId);

        mockMvc.perform(post("/api/orders/{orderId}/modification/refund-confirm", realOrderId)
                        .with(user(ownerId.toString()).roles("OWNER")))
                .andExpect(status().isConflict());

        org.assertj.core.api.Assertions.assertThat(orderRepository.findById(realOrderId).get().getStatus())
                .isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("N8：協商結清後查詢進行中的變更請求 → 204 No Content（正常空狀態），不再是 409")
    void should_Return204_When_NoActiveModificationRequest() throws Exception {
        // 已確認收到退款：訂單回到 CONFIRMED、變更請求為終態 M_DONE
        saveModRequest("CONFIRMED", "M_DONE", -500);

        for (UUID requester : List.of(ownerId, sitterId)) {
            TokenContext.setUserId(requester);
            String role = requester.equals(ownerId) ? "OWNER" : "SITTER";
            String body = mockMvc.perform(get("/api/orders/{orderId}/modification", realOrderId)
                            .with(user(requester.toString()).roles(role)))
                    .andExpect(status().isNoContent())
                    .andReturn().getResponse().getContentAsString();
            org.assertj.core.api.Assertions.assertThat(body).isEmpty();
        }
    }

    @Test
    @DisplayName("C2：查詢變更請求回傳 orderStatus，前端據此收斂可執行動作")
    void should_ReturnOrderStatus_When_QueryingModificationRequest() throws Exception {
        saveModRequest("REFUND_VERIFY", "M_DONE", -500);
        TokenContext.setUserId(sitterId);

        mockMvc.perform(get("/api/orders/{orderId}/modification", realOrderId)
                        .with(user(sitterId.toString()).roles("SITTER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("M_DONE"))
                .andExpect(jsonPath("$.orderStatus").value("REFUND_VERIFY"))
                .andExpect(jsonPath("$.diffAmount").value(-500));
    }

    // ===== C4 迴歸：退款憑證改為上傳圖片 + 私有物件 + 簽名網址，不再接受/回傳任意外部網址 =====

    @Test
    @DisplayName("C4：退款憑證非圖片格式 → 400，不上傳")
    void should_Return400_When_RefundProofIsNotImage() throws Exception {
        saveModRequest("REFUND_VERIFY", "M_DONE", -500);
        TokenContext.setUserId(sitterId);

        mockMvc.perform(multipart("/api/orders/{orderId}/modification/refund-proof", realOrderId)
                        .file(new org.springframework.mock.web.MockMultipartFile("file", "x.html", "text/html", "<a>".getBytes()))
                        .with(user(sitterId.toString()).roles("SITTER")))
                .andExpect(status().isBadRequest());
        verify(mediaStorageService, never()).uploadRefundProof(any(), any(), any());
    }

    @Test
    @DisplayName("C4：退款憑證超過 5MB → 400，不上傳")
    void should_Return400_When_RefundProofTooLarge() throws Exception {
        saveModRequest("REFUND_VERIFY", "M_DONE", -500);
        TokenContext.setUserId(sitterId);

        mockMvc.perform(multipart("/api/orders/{orderId}/modification/refund-proof", realOrderId)
                        .file(new org.springframework.mock.web.MockMultipartFile("file", "big.jpg", MediaType.IMAGE_JPEG_VALUE,
                                new byte[5 * 1024 * 1024 + 1]))
                        .with(user(sitterId.toString()).roles("SITTER")))
                .andExpect(status().isBadRequest());
        verify(mediaStorageService, never()).uploadRefundProof(any(), any(), any());
    }

    @Test
    @DisplayName("C4：舊的 JSON 網址字串上傳方式（舊版 PWA 快取）不再被接受 → 400（不是 500），不寫入任何網址")
    void should_Reject_When_RefundProofSubmittedAsUrlString() throws Exception {
        ModificationRequest modReq = saveModRequest("REFUND_VERIFY", "M_DONE", -500);
        TokenContext.setUserId(sitterId);

        mockMvc.perform(post("/api/orders/{orderId}/modification/refund-proof", realOrderId)
                        .with(user(sitterId.toString()).roles("SITTER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refundProofUrl\":\"https://evil.example.com/phish\"}"))
                .andExpect(status().isBadRequest());

        org.assertj.core.api.Assertions.assertThat(modRequestRepository.findById(modReq.getId()).get().getRefundProofUrl())
                .isNull();
    }

    @Test
    @DisplayName("C4：飼主/保母可取退款憑證簽名網址（10 分鐘），簽的是 refund-proofs/ 私有物件")
    void should_ReturnRefundProofSignedUrl_ForOrderParties() throws Exception {
        ModificationRequest modReq = saveModRequest("REFUND_VERIFY", "M_DONE", -500);
        String key = "refund-proofs/2026-10-24/" + realOrderId + "_abc.jpg";
        modReq.setRefundProofUrl(key);
        modRequestRepository.save(modReq);
        when(mediaStorageService.resolveObjectKey(any())).thenAnswer(inv -> inv.getArgument(0));
        when(mediaStorageService.generateSignedUrl(eq(key), any()))
                .thenReturn("https://storage.googleapis.com/b/" + key + "?X-Goog-Signature=abc");

        for (var party : List.of(List.of(ownerId.toString(), "OWNER"), List.of(sitterId.toString(), "SITTER"))) {
            TokenContext.setUserId(UUID.fromString(party.get(0)));
            mockMvc.perform(get("/api/orders/{orderId}/modification/refund-proof/signed-url", realOrderId)
                            .with(user(party.get(0)).roles(party.get(1))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.signedUrl").value("https://storage.googleapis.com/b/" + key + "?X-Goog-Signature=abc"))
                    .andExpect(jsonPath("$.expiresInSeconds").value(600));
        }
        verify(mediaStorageService, org.mockito.Mockito.times(2))
                .generateSignedUrl(eq(key), eq(java.time.Duration.ofMinutes(10)));
    }

    @Test
    @DisplayName("C4 (BOLA)：非訂單當事人取退款憑證簽名網址 → 403，且完全不簽名")
    void should_Return403_When_UnrelatedUserRequestsRefundProofSignedUrl() throws Exception {
        ModificationRequest modReq = saveModRequest("REFUND_VERIFY", "M_DONE", -500);
        modReq.setRefundProofUrl("refund-proofs/2026-10-24/x.jpg");
        modRequestRepository.save(modReq);
        User unrelated = userRepository.save(User.builder().email("unrelated-refund@test.com").passwordHash("hash").role("OWNER").build());
        TokenContext.setUserId(unrelated.getId());

        mockMvc.perform(get("/api/orders/{orderId}/modification/refund-proof/signed-url", realOrderId)
                        .with(user(unrelated.getId().toString()).roles("OWNER")))
                .andExpect(status().isForbidden());
        verify(mediaStorageService, never()).generateSignedUrl(any(), any());
    }

    @Test
    @DisplayName("C4：2026-10 前保母手填的外部網址 → 409，不簽名也不回傳該網址；未上傳 → 409")
    void should_Return409_When_RefundProofIsLegacyExternalUrlOrMissing() throws Exception {
        ModificationRequest modReq = saveModRequest("REFUND_VERIFY", "M_DONE", -500);
        when(mediaStorageService.resolveObjectKey(any())).thenAnswer(inv -> inv.getArgument(0));
        TokenContext.setUserId(ownerId);

        mockMvc.perform(get("/api/orders/{orderId}/modification/refund-proof/signed-url", realOrderId)
                        .with(user(ownerId.toString()).roles("OWNER")))
                .andExpect(status().isConflict());

        modReq.setRefundProofUrl("https://evil.example.com/phish.jpg");
        modRequestRepository.save(modReq);
        String body = mockMvc.perform(get("/api/orders/{orderId}/modification/refund-proof/signed-url", realOrderId)
                        .with(user(ownerId.toString()).roles("OWNER")))
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(body).doesNotContain("evil.example.com");
        verify(mediaStorageService, never()).generateSignedUrl(any(), any());
    }

    @Test
    @DisplayName("C4：查詢變更請求只回 hasRefundProof，不回任何憑證網址或 Object Key")
    void should_ExposeOnlyHasRefundProof_When_QueryingModificationRequest() throws Exception {
        ModificationRequest modReq = saveModRequest("REFUND_VERIFY", "M_DONE", -500);
        modReq.setRefundProofUrl("refund-proofs/2026-10-24/x.jpg");
        modRequestRepository.save(modReq);
        TokenContext.setUserId(ownerId);

        String body = mockMvc.perform(get("/api/orders/{orderId}/modification", realOrderId)
                        .with(user(ownerId.toString()).roles("OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasRefundProof").value(true))
                .andExpect(jsonPath("$.refundProofUrl").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(body).doesNotContain("refund-proofs/");
    }
}
