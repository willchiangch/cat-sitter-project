package com.petsitter.application.service;

import com.petsitter.application.dto.BookingRequest;
import com.petsitter.application.dto.QuoteRequest;
import com.petsitter.application.exception.AuthPlanLimitException;
import com.petsitter.domain.model.Order;
import com.petsitter.domain.model.ServicePlan;
import com.petsitter.domain.model.Subscription;
import com.petsitter.domain.model.User;
import com.petsitter.domain.repository.*;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

@Slf4j
@SpringBootTest
@Testcontainers
@ActiveProfiles("local")
@WithMockUser(roles = "SITTER")
@DisplayName("TS-006: 保母報價審核與快照 Service 測試")
class OrderEvaluationTest {

    static {
        // 強制設定 Docker API 版本與停用 Ryuk，解決 Windows 環境相容性問題
        System.setProperty("com.github.dockerjava.api.version", "1.44");
        System.setProperty("testcontainers.ryuk.disabled", "true");
    }

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private BookingService bookingService;

    @Autowired
    private EvaluationService evaluationService;

    @Autowired
    private ConfirmOrderService confirmOrderService;

    @Autowired
    private OrderSnapshotRepository orderSnapshotRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ServicePlanRepository servicePlanRepository;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private VisitRepository visitRepository;

    @Autowired
    private ProfileRepository profileRepository;

    private UUID sitterId;
    private UUID ownerId;
    private UUID planId;

    @BeforeEach
    void setUp() {
        visitRepository.deleteAll();
        orderSnapshotRepository.deleteAll();
        orderRepository.deleteAll();
        profileRepository.deleteAll();
        subscriptionRepository.deleteAll();
        servicePlanRepository.deleteAll();
        userRepository.deleteAll();

        User sitter = User.builder().email("sitter@example.com").passwordHash("hash").role("SITTER").build();
        User owner = User.builder().email("owner@example.com").passwordHash("hash").role("OWNER").build();
        userRepository.saveAllAndFlush(List.of(sitter, owner));
        
        // 建立已認證且開放的保母 Profile
        profileRepository.save(com.petsitter.domain.model.Profile.builder()
                .userId(sitter.getId())
                .type("SITTER")
                .kycStatus("VERIFIED")
                .isOpen(true)
                .build());

        this.sitterId = sitter.getId();
        this.ownerId = owner.getId();

        ServicePlan plan = ServicePlan.builder()
                .sitter(sitter).name("專業餵食").price(500L).dailyCapacity(5).build();
        servicePlanRepository.saveAndFlush(plan);
        this.planId = plan.getId();
    }

    @Test
    @DisplayName("TS-006-01: 報價金額快照與不回溯性")
    void ts006_01_should_KeepPriceConsistent_When_PlanPriceChangesAfterQuote() {
        // 1. Given: 建立一筆預約 (1 天, 500元)
        com.petsitter.application.dto.BookingItemRequest item = com.petsitter.application.dto.BookingItemRequest.builder()
                .planId(planId)
                .dates(List.of(LocalDate.of(2026, 6, 1)))
                .timesPerDay(1)
                .build();
        UUID orderId = bookingService.createBooking(BookingRequest.builder()
                .sitterId(sitterId).ownerId(ownerId)
                .items(List.of(item))
                .expectedTotalAmount(500)
                .idempotencyKey("quote-test-1").build());

        Order order = orderRepository.findById(orderId).orElseThrow();
        
        // 給保母 PRO 方案，允許調價
        subscriptionRepository.save(Subscription.builder().sitter(userRepository.findById(sitterId).orElseThrow()).planTier("PRO").build());

        // 2. When: 保母送出報價 (加價 200)
        QuoteRequest quoteReq = QuoteRequest.builder()
                .adjustmentAmount(200)
                .expectedTotalAmount(700) // 500 + 200
                .adjustmentReason("連假加收")
                .version(order.getVersion())
                .build();
        evaluationService.sendQuote(sitterId, orderId, quoteReq, "send-quote-test-1");

        // 3. 隨後修改原始方案單價 (從 500 改為 1000)
        ServicePlan plan = servicePlanRepository.findById(planId).orElseThrow();
        plan.setPrice(1000L);
        servicePlanRepository.saveAndFlush(plan);

        // 4. Then: 訂單總額必須保持 700，不應變為 1200
        Order finalOrder = orderRepository.findById(orderId).orElseThrow();
        assertThat(finalOrder.getTotalAmount()).as("報價後的訂單總額應受快照保護，不隨方案調價變動").isEqualTo(700);
        assertThat(finalOrder.getStatus()).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    @DisplayName("TS-006-02: SaaS 方案等級調價限制 (FREE 方案禁止加價)")
    void ts006_02_should_ThrowAuthPlanLimitException_When_FreePlanTriesToAdjustPrice() {
        // 1. Given: 建立預約，並將保母設為 FREE 方案
        subscriptionRepository.save(Subscription.builder()
                .sitter(userRepository.findById(sitterId).orElseThrow())
                .planTier("FREE").build());
                
        com.petsitter.application.dto.BookingItemRequest item = com.petsitter.application.dto.BookingItemRequest.builder()
                .planId(planId)
                .dates(List.of(LocalDate.of(2026, 6, 1)))
                .timesPerDay(1)
                .build();
        UUID orderId = bookingService.createBooking(BookingRequest.builder()
                .sitterId(sitterId).ownerId(ownerId)
                .items(List.of(item))
                .expectedTotalAmount(500)
                .idempotencyKey("quote-test-2").build());
        Order order = orderRepository.findById(orderId).orElseThrow();

        // 2. When: 保母嘗試調增 100 元
        QuoteRequest quoteReq = QuoteRequest.builder()
                .adjustmentAmount(100)
                .expectedTotalAmount(600)
                .version(order.getVersion())
                .build();

        // 3. Then: 預期系統拋出 SaaS 權限不足的自訂例外
        assertThatThrownBy(() -> evaluationService.sendQuote(sitterId, orderId, quoteReq, "send-quote-test-2"))
                .isInstanceOf(AuthPlanLimitException.class)
                .hasMessageContaining("當前方案不支援自訂報價");
    }

    @Test
    @DisplayName("TS-006-03: 樂觀鎖攔截 (保母報價時飼主同時撤單)")
    void ts006_03_should_ThrowOptimisticLockException_When_ConcurrentModification() {
        // 1. Given: 建立預約，保母升級為 PRO
        subscriptionRepository.save(Subscription.builder()
                .sitter(userRepository.findById(sitterId).orElseThrow())
                .planTier("PRO").build());
        
        com.petsitter.application.dto.BookingItemRequest item = com.petsitter.application.dto.BookingItemRequest.builder()
                .planId(planId)
                .dates(List.of(LocalDate.of(2026, 6, 1)))
                .timesPerDay(1)
                .build();
        UUID orderId = bookingService.createBooking(BookingRequest.builder()
                .sitterId(sitterId).ownerId(ownerId)
                .items(List.of(item))
                .expectedTotalAmount(500)
                .idempotencyKey("quote-test-3").build());
                
        Order orderForSitter = orderRepository.findById(orderId).orElseThrow();
        
        // 2. 模擬併發：飼主（或系統）先一步更新了訂單（例如標註為爭議或更新備註）
        Order orderForOther = orderRepository.findById(orderId).orElseThrow();
        orderForOther.setAdjustmentReason("Concurrent Update");
        orderRepository.saveAndFlush(orderForOther); // 寫入 DB，此時 Version 遞增

        // 3. When & Then: 保母用舊的 Version 試圖送出報價，預期拋出樂觀鎖例外
        QuoteRequest quoteReq = QuoteRequest.builder()
                .adjustmentAmount(0)
                .expectedTotalAmount(500)
                .version(orderForSitter.getVersion()) // 帶入過期的 version
                .build();

        assertThatThrownBy(() -> evaluationService.sendQuote(sitterId, orderId, quoteReq, "send-quote-test-3"))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    // ---------------------------------------------------------------------------------------------
    // TS-006-04 ~ 06：議價報價路徑的容量保護（backlog G1）
    // 報價後訂單直接轉 PENDING_PAYMENT（SD-006 §2.1），容量在「報價當下」就被佔用，
    // 所以 sendQuote 必須和原價接單 ConfirmOrderService 走同一組鎖 + 鎖內 count（CapacityGuard）。
    // ---------------------------------------------------------------------------------------------

    private void setDailyCapacity(int capacity) {
        ServicePlan plan = servicePlanRepository.findById(planId).orElseThrow();
        plan.setDailyCapacity(capacity);
        servicePlanRepository.saveAndFlush(plan);
    }

    private UUID createPendingOrder(LocalDate date, String key) {
        return bookingService.createBooking(BookingRequest.builder()
                .sitterId(sitterId).ownerId(ownerId)
                .items(List.of(com.petsitter.application.dto.BookingItemRequest.builder()
                        .planId(planId).dates(List.of(date)).timesPerDay(1).build()))
                .expectedTotalAmount(500)
                .idempotencyKey(key).build());
    }

    /** 原價報價（adjustment 0，FREE 方案可用），帶入訂單目前 version。 */
    private void quoteAtOriginalPrice(UUID orderId, String idempotencyKey) {
        Order order = orderRepository.findById(orderId).orElseThrow();
        evaluationService.sendQuote(sitterId, orderId, QuoteRequest.builder()
                .adjustmentAmount(0)
                .expectedTotalAmount(order.getTotalAmount())
                .version(order.getVersion())
                .build(), idempotencyKey);
    }

    /**
     * 每個 action 一個執行緒，用 CyclicBarrier 同時起跑。回傳 [成功數, CapacityFullException 數]；
     * 其他例外（例如 409 樂觀鎖、或沒有保護時根本不會丟例外）讓測試直接失敗或由數量斷言抓出。
     */
    private int[] runConcurrently(List<Runnable> actions) throws Exception {
        java.util.concurrent.ExecutorService executor =
                java.util.concurrent.Executors.newFixedThreadPool(actions.size());
        java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(actions.size());
        java.util.concurrent.atomic.AtomicInteger success = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger full = new java.util.concurrent.atomic.AtomicInteger();
        try {
            List<java.util.concurrent.CompletableFuture<Void>> futures = new java.util.ArrayList<>();
            for (Runnable action : actions) {
                futures.add(java.util.concurrent.CompletableFuture.runAsync(() -> {
                    try {
                        barrier.await();
                        action.run();
                        success.incrementAndGet();
                    } catch (com.petsitter.application.exception.CapacityFullException e) {
                        full.incrementAndGet();
                    } catch (Exception e) {
                        throw new IllegalStateException("接單 / 報價應只有成功或 CapacityFullException 兩種結果", e);
                    }
                }, executor));
            }
            java.util.concurrent.CompletableFuture.allOf(futures.toArray(new java.util.concurrent.CompletableFuture[0]))
                    .get(30, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        return new int[] { success.get(), full.get() };
    }

    private long countStatus(List<UUID> orderIds, String status) {
        return orderRepository.findAllById(orderIds).stream().filter(o -> status.equals(o.getStatus())).count();
    }

    private long countBooked(LocalDate date) {
        return visitRepository.countBookedVisitsBySitterIdAndDate(
                sitterId, date.atStartOfDay().atOffset(java.time.ZoneOffset.UTC));
    }

    @Test
    @DisplayName("TS-006-04: 容量 1 已被原價接單佔滿，同日另一張單改用報價也應 CapacityFull，訂單維持 PENDING 且不建快照")
    void ts006_04_should_RejectQuote_When_DayAlreadyFullByConfirm() {
        setDailyCapacity(1);
        LocalDate date = LocalDate.of(2026, 9, 1);
        UUID confirmed = createPendingOrder(date, "g1-seq-a");
        UUID quoted = createPendingOrder(date, "g1-seq-b");

        confirmOrderService.confirmOrder(sitterId, confirmed);

        assertThatThrownBy(() -> quoteAtOriginalPrice(quoted, "g1-seq-quote"))
                .isInstanceOf(com.petsitter.application.exception.CapacityFullException.class)
                .hasMessageContaining(date.toString());

        assertThat(orderRepository.findById(quoted).orElseThrow().getStatus()).isEqualTo("PENDING");
        assertThat(orderSnapshotRepository.findAll().stream()
                .filter(snap -> snap.getOrder().getId().equals(quoted)).count()).isZero();
        assertThat(countBooked(date)).isEqualTo(1);
        // 容量不足時整筆 rollback（含冪等鍵），保母調高容量後用同一顆 key 重試不會被當成重送
        setDailyCapacity(2);
        quoteAtOriginalPrice(quoted, "g1-seq-quote");
        assertThat(orderRepository.findById(quoted).orElseThrow().getStatus()).isEqualTo("PENDING_PAYMENT");
        assertThat(countBooked(date)).isEqualTo(2);
    }

    @Test
    @DisplayName("TS-006-05: 容量 1、同日兩張單一張原價接單一張報價同時起跑，只成功一張（兩條路徑共用同一組鎖）")
    void ts006_05_should_PreventOverselling_When_ConfirmAndQuoteRace() throws Exception {
        setDailyCapacity(1);
        for (int round = 0; round < 5; round++) {
            LocalDate date = LocalDate.of(2026, 9, 10).plusDays(round);
            UUID a = createPendingOrder(date, "g1-mix-a-" + round);
            UUID b = createPendingOrder(date, "g1-mix-b-" + round);
            String quoteKey = "g1-mix-quote-" + round;

            int[] result = runConcurrently(List.of(
                    () -> confirmOrderService.confirmOrder(sitterId, a),
                    () -> quoteAtOriginalPrice(b, quoteKey)));

            assertThat(result[0]).as("第 %d 輪成功數（容量 1）", round + 1).isEqualTo(1);
            assertThat(result[1]).as("第 %d 輪 CapacityFullException 數", round + 1).isEqualTo(1);
            assertThat(countStatus(List.of(a, b), "PENDING_PAYMENT")).isEqualTo(1);
            assertThat(countStatus(List.of(a, b), "PENDING")).isEqualTo(1);
            assertThat(countBooked(date)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("TS-006-06: 容量 1、同日兩張單同時報價，只成功一張")
    void ts006_06_should_PreventOverselling_When_ConcurrentQuotes() throws Exception {
        setDailyCapacity(1);
        for (int round = 0; round < 5; round++) {
            LocalDate date = LocalDate.of(2026, 9, 20).plusDays(round);
            UUID a = createPendingOrder(date, "g1-qq-a-" + round);
            UUID b = createPendingOrder(date, "g1-qq-b-" + round);
            String keyA = "g1-qq-quote-a-" + round;
            String keyB = "g1-qq-quote-b-" + round;

            int[] result = runConcurrently(List.of(
                    () -> quoteAtOriginalPrice(a, keyA),
                    () -> quoteAtOriginalPrice(b, keyB)));

            assertThat(result[0]).as("第 %d 輪成功數（容量 1）", round + 1).isEqualTo(1);
            assertThat(result[1]).as("第 %d 輪 CapacityFullException 數", round + 1).isEqualTo(1);
            assertThat(countStatus(List.of(a, b), "PENDING_PAYMENT")).isEqualTo(1);
            assertThat(countBooked(date)).isEqualTo(1);
            assertThat(orderSnapshotRepository.findAll().stream()
                    .filter(snap -> List.of(a, b).contains(snap.getOrder().getId())).count()).isEqualTo(1);
        }
    }
}
