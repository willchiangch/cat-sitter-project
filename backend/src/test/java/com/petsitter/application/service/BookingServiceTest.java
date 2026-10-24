package com.petsitter.application.service;

import com.petsitter.application.dto.BookingRequest;
import com.petsitter.domain.model.Order;
import com.petsitter.domain.model.ServicePlan;
import com.petsitter.domain.model.User;
import com.petsitter.domain.repository.*;
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
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("local")
@WithMockUser(roles = "OWNER")
@DisplayName("TS-005: 預約建單與併發接單狀態轉移測試")
class BookingServiceTest {

    static {
        System.setProperty("com.github.dockerjava.api.version", "1.44");
        System.setProperty("testcontainers.ryuk.disabled", "true");
    }

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private BookingService bookingService;

    @Autowired
    private ConfirmOrderService confirmOrderService;

    @Autowired
    private MockMvc mockMvc;

    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper =
            new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private VisitRepository visitRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private ServicePlanRepository servicePlanRepository;

    @Autowired
    private OrderSnapshotRepository orderSnapshotRepository;

    @Autowired
    private ProfileRepository profileRepository;

    @Autowired
    private SitterQuestionRepository sitterQuestionRepository;

    private UUID ownerId;
    private UUID sitterId;
    private UUID planId;

    @BeforeEach
    void setUp() {
        subscriptionRepository.deleteAll();
        sitterQuestionRepository.deleteAll();
        visitRepository.deleteAll();
        orderSnapshotRepository.deleteAll();
        orderRepository.deleteAll();
        profileRepository.deleteAll();
        servicePlanRepository.deleteAll();
        userRepository.deleteAll();

        User owner = userRepository.save(User.builder().email("owner@test.com").passwordHash("hash").role("OWNER").build());
        User sitter = userRepository.save(User.builder().email("sitter@test.com").passwordHash("hash").role("SITTER").build());
        
        // 建立已認證且開放的保母 Profile 檔案以通過預約門禁
        profileRepository.save(com.petsitter.domain.model.Profile.builder()
                .userId(sitter.getId())
                .type("SITTER")
                .kycStatus("VERIFIED")
                .isOpen(true)
                .build());

        ServicePlan plan = servicePlanRepository.save(ServicePlan.builder()
                .sitter(sitter)
                .name("專業餵食")
                .price(500L)
                .build());

        this.ownerId = owner.getId();
        this.sitterId = sitter.getId();
        this.planId = plan.getId();
    }

    @Test
    @DisplayName("TS-005-01: 併發送單情境 (媒合式模型) - 應允許多筆 PENDING 訂單同時存在")
    void ts005_01_should_AllowMultiplePendingOrders_When_ConcurrentSubmission() {
        int concurrentCount = 5;
        List<CompletableFuture<UUID>> futures = new ArrayList<>();
        
        com.petsitter.application.dto.BookingItemRequest item = com.petsitter.application.dto.BookingItemRequest.builder()
                .planId(planId)
                .dates(List.of(LocalDate.of(2026, 6, 1)))
                .timesPerDay(1)
                .build();

        for (int i = 0; i < concurrentCount; i++) {
            final int index = i;
            futures.add(CompletableFuture.supplyAsync(() -> {
                return bookingService.createBooking(BookingRequest.builder()
                        .ownerId(ownerId)
                        .sitterId(sitterId)
                        .items(List.of(item))
                        .expectedTotalAmount(500)
                        .idempotencyKey("concurrent-key-" + index)
                        .build());
            }));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        long pendingCount = orderRepository.findAll().stream()
                .filter(o -> "PENDING".equals(o.getStatus()))
                .count();

        assertThat(pendingCount).isEqualTo(concurrentCount);
    }

    private List<UUID> createPendingOrders(LocalDate date, int count, String keyPrefix) {
        com.petsitter.application.dto.BookingItemRequest item = com.petsitter.application.dto.BookingItemRequest.builder()
                .planId(planId)
                .dates(List.of(date))
                .timesPerDay(1)
                .build();
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(bookingService.createBooking(BookingRequest.builder()
                    .ownerId(ownerId).sitterId(sitterId).items(List.of(item))
                    .expectedTotalAmount(500)
                    .idempotencyKey(keyPrefix + "-" + i).build()));
        }
        return ids;
    }

    private long countOrdersByStatus(List<UUID> orderIds, String status) {
        return orderRepository.findAllById(orderIds).stream().filter(o -> status.equals(o.getStatus())).count();
    }

    private List<Runnable> confirmActions(List<UUID> orderIds) {
        return orderIds.stream()
                .<Runnable>map(orderId -> () -> confirmOrderService.confirmOrder(sitterId, orderId))
                .toList();
    }

    @Test
    @DisplayName("TS-005-02: 同一張 PENDING 單同時兩次原價接單，只成功一次，另一個 409，快照只建一份")
    void ts005_02_should_AcceptOnce_When_SameOrderConfirmedConcurrently() throws Exception {
        // 每輪一張新單、兩個請求同時接同一張，多跑幾輪提高撞在同一瞬間的機率
        for (int round = 0; round < 5; round++) {
            LocalDate date = LocalDate.of(2026, 6, 1).plusDays(round);
            UUID orderId = createPendingOrders(date, 1, "dup-confirm-" + round).get(0);

            ConcurrentTransitionSupport.Result result =
                    ConcurrentTransitionSupport.runConcurrently(confirmActions(List.of(orderId, orderId)));

            assertThat(result.success()).as("第 %d 輪成功數", round + 1).isEqualTo(1);
            assertThat(result.failureStatuses()).as("第 %d 輪失敗回應（%s）", round + 1, result.failures())
                    .containsExactly(409);
            assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo("PENDING_PAYMENT");
            assertThat(orderSnapshotRepository.findAll().stream()
                    .filter(snap -> snap.getOrder().getId().equals(orderId)).count()).isEqualTo(1);
            // SD-008：接單不動 visit 狀態，行程維持 PENDING（舊死碼 BookingService.confirmBooking 會改成 CONFIRMED）
            assertThat(visitRepository.findByOrderId(orderId))
                    .extracting(com.petsitter.domain.model.Visit::getStatus)
                    .containsOnly("PENDING");
        }
    }

    @Test
    @DisplayName("TS-005-02b: 系統不做每日容量控管 - 同日三張單同時接單全部成功，各自建立快照")
    void ts005_02b_should_AcceptAllSameDayOrders_When_ConcurrentConfirm() throws Exception {
        // PRD-005 / PRD-006：同日已有訂單不阻擋，接不接由保母自行判斷（2026-10 移除 dailyCapacity）
        for (int round = 0; round < 3; round++) {
            LocalDate date = LocalDate.of(2026, 7, 1).plusDays(round);
            List<UUID> orders = createPendingOrders(date, 3, "same-day-" + round);

            ConcurrentTransitionSupport.Result result =
                    ConcurrentTransitionSupport.runConcurrently(confirmActions(orders));

            assertThat(result.success()).as("第 %d 輪成功數（%s）", round + 1, result.failures()).isEqualTo(3);
            assertThat(result.failureStatuses()).isEmpty();
            assertThat(countOrdersByStatus(orders, "PENDING_PAYMENT")).isEqualTo(3);
            assertThat(orderSnapshotRepository.findAll().stream()
                    .filter(snap -> orders.contains(snap.getOrder().getId())).count()).isEqualTo(3);
        }
    }

    @Test
    @DisplayName("TS-005-06: POST /api/orders/booking 回應契約 - 201 且 body 只有 { orderId: <uuid> }，不包 data 外層")
    void ts005_06_should_ReturnOrderIdObject_When_BookingCreated() throws Exception {
        BookingRequest request = BookingRequest.builder()
                .ownerId(ownerId)
                .sitterId(sitterId)
                .items(List.of(com.petsitter.application.dto.BookingItemRequest.builder()
                        .planId(planId)
                        .dates(List.of(LocalDate.of(2026, 8, 1)))
                        .timesPerDay(1)
                        .build()))
                .expectedTotalAmount(500)
                .build();

        String body = mockMvc.perform(post("/api/orders/booking")
                        .with(user(ownerId.toString()).roles("OWNER"))
                        .header("Idempotency-Key", "contract-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderId").isString())
                .andExpect(jsonPath("$.data").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        // 前端 PublicBookingPage 只認這個形狀（backlog F3 移除了 `res.orderId || res` fallback），契約變了要一起改前端
        java.util.Map<String, Object> json = objectMapper.readValue(body,
                new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {});
        assertThat(json.keySet()).containsExactly("orderId");
        UUID orderId = UUID.fromString((String) json.get("orderId"));
        assertThat(orderRepository.findById(orderId)).isPresent();
    }

    @Test
    @DisplayName("TS-005-03: 複合方案預約驗證 (Multi-Plan / Multi-Visit) - 驗證趟次正確建立")
    void ts005_03_should_CalculateTotalCorrectly_When_MultiPlanSubmitted() {
        ServicePlan planB = servicePlanRepository.save(ServicePlan.builder()
                .sitter(userRepository.findById(sitterId).orElseThrow())
                .name("基礎餵食")
                .price(800L)
                .build());

        com.petsitter.application.dto.BookingItemRequest item1 = com.petsitter.application.dto.BookingItemRequest.builder()
                .planId(planId) // Plan A ($500)
                .dates(List.of(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2)))
                .timesPerDay(2)
                .build();

        com.petsitter.application.dto.BookingItemRequest item2 = com.petsitter.application.dto.BookingItemRequest.builder()
                .planId(planB.getId()) // Plan B ($800)
                .dates(List.of(LocalDate.of(2026, 1, 3)))
                .timesPerDay(1)
                .build();

        UUID orderId = bookingService.createBooking(BookingRequest.builder()
                .ownerId(ownerId)
                .sitterId(sitterId)
                .items(List.of(item1, item2))
                .expectedTotalAmount(2800) // (500*4) + (800*1)
                .idempotencyKey("multi-plan-order")
                .build());

        Order savedOrder = orderRepository.findById(orderId).orElseThrow();
        assertThat(savedOrder.getItems()).hasSize(2);
        
        List<com.petsitter.domain.model.Visit> visits = visitRepository.findByOrderId(orderId);
        // (1/1 x2) + (1/2 x2) + (1/3 x1) = 5 visits
        assertThat(visits).hasSize(5);

        long planAVisits = visits.stream().filter(v -> v.getPlanId().equals(planId)).count();
        long planBVisits = visits.stream().filter(v -> v.getPlanId().equals(planB.getId())).count();
        assertThat(planAVisits).isEqualTo(4);
        assertThat(planBVisits).isEqualTo(1);
    }

    @Test
    @DisplayName("PRD-004/005: 必填問卷題目未作答時應擋下送單 (AC-4)")
    void should_RejectBooking_When_RequiredQuestionUnanswered() {
        com.petsitter.domain.model.SitterQuestion question = sitterQuestionRepository.save(
                com.petsitter.domain.model.SitterQuestion.builder()
                        .sitter(userRepository.findById(sitterId).orElseThrow())
                        .questionText("毛孩是否會攻擊人？")
                        .answerType("RADIO")
                        .options(List.of("會", "不會"))
                        .required(true)
                        .sortOrder(0)
                        .isActive(true)
                        .build());

        com.petsitter.application.dto.BookingItemRequest item = com.petsitter.application.dto.BookingItemRequest.builder()
                .planId(planId)
                .dates(List.of(LocalDate.of(2026, 6, 1)))
                .timesPerDay(1)
                .build();

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> bookingService.createBooking(BookingRequest.builder()
                        .ownerId(ownerId).sitterId(sitterId).items(List.of(item))
                        .expectedTotalAmount(500)
                        .idempotencyKey("no-answer-key").build()));

        UUID orderId = bookingService.createBooking(BookingRequest.builder()
                .ownerId(ownerId).sitterId(sitterId).items(List.of(item))
                .expectedTotalAmount(500)
                .answers(List.of(com.petsitter.application.dto.QuestionnaireAnswerRequest.builder()
                        .questionId(question.getId())
                        .answerValues(List.of("不會"))
                        .build()))
                .idempotencyKey("with-answer-key").build());

        Order savedOrder = orderRepository.findById(orderId).orElseThrow();
        assertThat(savedOrder.getQuestionnaireAnswers()).hasSize(1);
        assertThat(savedOrder.getQuestionnaireAnswers().get(0).getAnswerValues()).containsExactly("不會");
    }
}
