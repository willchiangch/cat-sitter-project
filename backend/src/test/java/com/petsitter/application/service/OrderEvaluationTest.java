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
                .sitter(sitter).name("專業餵食").price(500L).build();
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
        UUID orderId = bookingService.createBooking(ownerId, BookingRequest.builder()
                .sitterId(sitterId)
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
        UUID orderId = bookingService.createBooking(ownerId, BookingRequest.builder()
                .sitterId(sitterId)
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
        UUID orderId = bookingService.createBooking(ownerId, BookingRequest.builder()
                .sitterId(sitterId)
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
    // TS-006-04 ~ 06：系統不做每日容量控管（PRD-005 / PRD-006，2026-10 移除 dailyCapacity 與 CapacityGuard），
    // 但「同一張訂單」只能從 PENDING 轉出一次：原價接單與報價、或兩次報價同時打同一張單，只能成功一個，另一個 409。
    // ---------------------------------------------------------------------------------------------

    private UUID createPendingOrder(LocalDate date, String key) {
        return bookingService.createBooking(ownerId, BookingRequest.builder()
                .sitterId(sitterId)
                .items(List.of(com.petsitter.application.dto.BookingItemRequest.builder()
                        .planId(planId).dates(List.of(date)).timesPerDay(1).build()))
                .expectedTotalAmount(500)
                .idempotencyKey(key).build());
    }

    /** 原價報價（adjustment 0，FREE 方案可用），version 由呼叫方帶入，併發時兩個請求拿同一個 version。 */
    private void quoteAtOriginalPrice(UUID orderId, int version, String idempotencyKey) {
        evaluationService.sendQuote(sitterId, orderId, QuoteRequest.builder()
                .adjustmentAmount(0)
                .expectedTotalAmount(500)
                .version(version)
                .build(), idempotencyKey);
    }

    private int versionOf(UUID orderId) {
        return orderRepository.findById(orderId).orElseThrow().getVersion();
    }

    private long snapshotCount(UUID orderId) {
        return orderSnapshotRepository.findAll().stream()
                .filter(snap -> snap.getOrder().getId().equals(orderId)).count();
    }

    @Test
    @DisplayName("TS-006-04: 不做容量控管 - 同日已有原價接單的訂單，另一張同日單報價仍成功並建立快照")
    void ts006_04_should_AllowQuote_When_SameDayAlreadyHasConfirmedOrder() {
        LocalDate date = LocalDate.of(2026, 9, 1);
        UUID confirmed = createPendingOrder(date, "seq-a");
        UUID quoted = createPendingOrder(date, "seq-b");

        confirmOrderService.confirmOrder(sitterId, confirmed);
        quoteAtOriginalPrice(quoted, versionOf(quoted), "seq-quote");

        assertThat(orderRepository.findById(confirmed).orElseThrow().getStatus()).isEqualTo("PENDING_PAYMENT");
        assertThat(orderRepository.findById(quoted).orElseThrow().getStatus()).isEqualTo("PENDING_PAYMENT");
        assertThat(snapshotCount(quoted)).isEqualTo(1);
    }

    @Test
    @DisplayName("TS-006-05: 同一張單原價接單與報價同時起跑，只成功一個，另一個 409，快照只建一份")
    void ts006_05_should_TransitionOnce_When_ConfirmAndQuoteRaceOnSameOrder() throws Exception {
        for (int round = 0; round < 5; round++) {
            LocalDate date = LocalDate.of(2026, 9, 10).plusDays(round);
            UUID orderId = createPendingOrder(date, "mix-" + round);
            int version = versionOf(orderId);
            String quoteKey = "mix-quote-" + round;

            ConcurrentTransitionSupport.Result result = ConcurrentTransitionSupport.runConcurrently(List.of(
                    () -> confirmOrderService.confirmOrder(sitterId, orderId),
                    () -> quoteAtOriginalPrice(orderId, version, quoteKey)));

            assertThat(result.success()).as("第 %d 輪成功數", round + 1).isEqualTo(1);
            assertThat(result.failureStatuses()).as("第 %d 輪失敗回應（%s）", round + 1, result.failures())
                    .containsExactly(409);
            assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo("PENDING_PAYMENT");
            assertThat(snapshotCount(orderId)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("TS-006-06: 同一張單兩次報價（不同冪等鍵、同一 version）同時起跑，只成功一個，另一個 409")
    void ts006_06_should_QuoteOnce_When_ConcurrentQuotesOnSameOrder() throws Exception {
        for (int round = 0; round < 5; round++) {
            LocalDate date = LocalDate.of(2026, 9, 20).plusDays(round);
            UUID orderId = createPendingOrder(date, "qq-" + round);
            int version = versionOf(orderId);
            String keyA = "qq-quote-a-" + round;
            String keyB = "qq-quote-b-" + round;

            ConcurrentTransitionSupport.Result result = ConcurrentTransitionSupport.runConcurrently(List.of(
                    () -> quoteAtOriginalPrice(orderId, version, keyA),
                    () -> quoteAtOriginalPrice(orderId, version, keyB)));

            assertThat(result.success()).as("第 %d 輪成功數", round + 1).isEqualTo(1);
            assertThat(result.failureStatuses()).as("第 %d 輪失敗回應（%s）", round + 1, result.failures())
                    .containsExactly(409);
            assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo("PENDING_PAYMENT");
            assertThat(snapshotCount(orderId)).isEqualTo(1);
        }
    }
}
