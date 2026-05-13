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

    private UUID sitterId;
    private UUID ownerId;
    private UUID planId;

    @BeforeEach
    void setUp() {
        visitRepository.deleteAll();
        orderSnapshotRepository.deleteAll();
        orderRepository.deleteAll();
        subscriptionRepository.deleteAll();
        servicePlanRepository.deleteAll();
        userRepository.deleteAll();

        User sitter = User.builder().email("sitter@example.com").passwordHash("hash").role("SITTER").build();
        User owner = User.builder().email("owner@example.com").passwordHash("hash").role("OWNER").build();
        userRepository.saveAllAndFlush(List.of(sitter, owner));
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
        evaluationService.sendQuote(sitterId, orderId, quoteReq);

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
                .idempotencyKey("quote-test-2").build());
        Order order = orderRepository.findById(orderId).orElseThrow();

        // 2. When: 保母嘗試調增 100 元
        QuoteRequest quoteReq = QuoteRequest.builder()
                .adjustmentAmount(100)
                .expectedTotalAmount(600)
                .version(order.getVersion())
                .build();

        // 3. Then: 預期系統拋出 SaaS 權限不足的自訂例外
        assertThatThrownBy(() -> evaluationService.sendQuote(sitterId, orderId, quoteReq))
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

        assertThatThrownBy(() -> evaluationService.sendQuote(sitterId, orderId, quoteReq))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }
}
