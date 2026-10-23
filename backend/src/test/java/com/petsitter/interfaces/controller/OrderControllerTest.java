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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
    @DisplayName("FREE 方案保母若嘗試調價 (adjustment != 0)，應被 AOP 攔截並回傳 403")
    void should_BlockAdjustment_For_FreePlan() throws Exception {
        // Given: FREE 方案
        subscriptionRepository.save(Subscription.builder().sitter(userRepository.findById(sitterId).get()).planTier("FREE").build());

        QuoteRequest request = QuoteRequest.builder()
                .adjustmentAmount(100)
                .expectedTotalAmount(600)
                .version(1)
                .build();

        // When & Then
        mockMvc.perform(post("/api/orders/{orderId}/quote", orderId)
                .with(user("test").roles("SITTER"))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .param("sitterId", sitterId.toString())
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

        // When & Then
        mockMvc.perform(post("/api/orders/{orderId}/quote", orderId)
                .with(user("test").roles("SITTER"))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .param("sitterId", sitterId.toString())
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

        mockMvc.perform(post("/api/orders/{orderId}/modification/refund-proof", realOrderId)
                .with(user(ownerId.toString()).roles("OWNER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refundProofUrl\":\"https://storage.googleapis.com/test/proof.jpg\"}"))
                .andExpect(status().isForbidden());
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

        mockMvc.perform(post("/api/orders/{orderId}/modification/refund-proof", realOrderId)
                .with(user(sitterId.toString()).roles("SITTER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refundProofUrl\":\"https://storage.googleapis.com/test/proof.jpg\"}"))
                .andExpect(status().isOk());

        var saved = modRequestRepository.findById(modReq.getId()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(saved.getRefundProofUrl())
                .isEqualTo("https://storage.googleapis.com/test/proof.jpg");
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

        mockMvc.perform(post("/api/orders/{orderId}/modification/refund-proof", realOrderId)
                        .with(user(sitterId.toString()).roles("SITTER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refundProofUrl\":\"https://evil.example.com/x.jpg\"}"))
                .andExpect(status().isConflict());

        org.assertj.core.api.Assertions.assertThat(modRequestRepository.findById(modReq.getId()).get().getRefundProofUrl())
                .isEqualTo("refund-proofs/2026-10-24/original.jpg");
    }

    @Test
    @DisplayName("C2 迴歸：補款分支 (PENDING_PAYMENT、差額為正) 不可上傳退款憑證，應 409")
    void should_Return409_When_RefundProofUploadedOnSupplementBranch() throws Exception {
        ModificationRequest modReq = saveModRequest("PENDING_PAYMENT", "M_DONE", 600);
        TokenContext.setUserId(sitterId);

        mockMvc.perform(post("/api/orders/{orderId}/modification/refund-proof", realOrderId)
                        .with(user(sitterId.toString()).roles("SITTER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refundProofUrl\":\"https://storage.googleapis.com/test/proof.jpg\"}"))
                .andExpect(status().isConflict());

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
}
