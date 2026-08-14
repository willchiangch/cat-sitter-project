package com.petsitter.interfaces.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petsitter.application.dto.QuoteRequest;
import com.petsitter.application.service.EvaluationService;
import com.petsitter.domain.model.Order;
import com.petsitter.domain.model.Subscription;
import com.petsitter.domain.model.User;
import com.petsitter.domain.model.Visit;
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

    @MockitoBean
    private EvaluationService evaluationService;

    private UUID sitterId;
    private UUID orderId;
    private UUID ownerId;
    private UUID realOrderId;
    private UUID visitId;

    @BeforeEach
    void setUp() {
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
}
