package com.petsitter.application.service;

import com.petsitter.application.dto.BookingRequest;
import com.petsitter.application.exception.CapacityFullException;
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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@ActiveProfiles("local")
@WithMockUser(roles = "OWNER")
@DisplayName("TS-005: 預約建單與併發接單容量控制測試")
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
                .dailyCapacity(1) // 關鍵：每日僅限 1 單
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

    /**
     * 讓 orderIds 這幾張單由 n 個執行緒同時打正式接單路徑 {@link ConfirmOrderService#confirmOrder}
     * （= POST /api/orders/{id}/confirm），回傳 [成功數, CapacityFullException 數]。其他例外直接讓測試失敗。
     */
    private int[] confirmConcurrently(List<UUID> orderIds) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(orderIds.size());
        java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(orderIds.size());
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger capacityFullCount = new AtomicInteger(0);
        try {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (UUID orderId : orderIds) {
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        barrier.await();
                        confirmOrderService.confirmOrder(sitterId, orderId);
                        successCount.incrementAndGet();
                    } catch (CapacityFullException e) {
                        capacityFullCount.incrementAndGet();
                    } catch (Exception e) {
                        throw new IllegalStateException("接單應只有成功或 CapacityFullException 兩種結果", e);
                    }
                }, executor));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(30, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        return new int[] { successCount.get(), capacityFullCount.get() };
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

    @Test
    @DisplayName("TS-005-02: 併發確認接單 (Advisory Lock) - 正式接單路徑 ConfirmOrderService 應防止超賣，容量 1 時兩張只成功一張")
    void ts005_02_should_PreventOverselling_When_SitterConcurrentConfirm() throws Exception {
        // 每輪換一天、各自兩張 PENDING 單同時接，多跑幾輪提高撞在同一瞬間的機率
        for (int round = 0; round < 5; round++) {
            LocalDate date = LocalDate.of(2026, 6, 1).plusDays(round);
            List<UUID> orders = createPendingOrders(date, 2, "cap1-" + round);

            int[] result = confirmConcurrently(orders);

            assertThat(result[0]).as("第 %d 輪成功接單數（容量 1）", round + 1).isEqualTo(1);
            assertThat(result[1]).as("第 %d 輪 CapacityFullException 數", round + 1).isEqualTo(1);
            assertThat(countOrdersByStatus(orders, "PENDING_PAYMENT")).isEqualTo(1);
            assertThat(countOrdersByStatus(orders, "PENDING")).isEqualTo(1);
            // 只有成功那張建立快照；失敗那張整筆 rollback
            assertThat(orderSnapshotRepository.findAll().stream()
                    .filter(snap -> orders.contains(snap.getOrder().getId())).count()).isEqualTo(1);
            // SD-008：接單不動 visit 狀態，行程維持 PENDING（舊死碼 BookingService.confirmBooking 會改成 CONFIRMED）
            for (UUID orderId : orders) {
                assertThat(visitRepository.findByOrderId(orderId))
                        .extracting(com.petsitter.domain.model.Visit::getStatus)
                        .containsOnly("PENDING");
            }
        }
    }

    @Test
    @DisplayName("TS-005-02b: 併發確認接單邊界 - 容量 2、三張同日單同時接，只成功兩張")
    void ts005_02b_should_AcceptUpToDailyCapacity_When_ConcurrentConfirmExceedsCapacity() throws Exception {
        ServicePlan plan = servicePlanRepository.findById(planId).orElseThrow();
        plan.setDailyCapacity(2);
        servicePlanRepository.save(plan);

        for (int round = 0; round < 5; round++) {
            LocalDate date = LocalDate.of(2026, 7, 1).plusDays(round);
            List<UUID> orders = createPendingOrders(date, 3, "cap2-" + round);

            int[] result = confirmConcurrently(orders);

            assertThat(result[0]).as("第 %d 輪成功接單數（容量 2）", round + 1).isEqualTo(2);
            assertThat(result[1]).as("第 %d 輪 CapacityFullException 數", round + 1).isEqualTo(1);
            assertThat(countOrdersByStatus(orders, "PENDING_PAYMENT")).isEqualTo(2);
            assertThat(visitRepository.countBookedVisitsBySitterIdAndDate(
                    sitterId, date.atStartOfDay().atOffset(java.time.ZoneOffset.UTC))).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("TS-005-03: 複合方案預約驗證 (Multi-Plan / Multi-Visit) - 驗證趟次正確建立")
    void ts005_03_should_CalculateTotalCorrectly_When_MultiPlanSubmitted() {
        ServicePlan planB = servicePlanRepository.save(ServicePlan.builder()
                .sitter(userRepository.findById(sitterId).orElseThrow())
                .name("基礎餵食")
                .dailyCapacity(5)
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
