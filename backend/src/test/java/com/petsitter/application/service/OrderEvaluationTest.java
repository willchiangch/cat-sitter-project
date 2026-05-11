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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Slf4j
@SpringBootTest
@Testcontainers
@Tag("TS-006")
@DisplayName("TS-006: 保母報價審核與快照測試")
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
        UUID orderId = bookingService.createBooking(BookingRequest.builder()
                .sitterId(sitterId).ownerId(ownerId).planId(planId)
                .dates(List.of(LocalDate.of(2026, 6, 1)))
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
    @DisplayName("TS-006-02: SaaS 方案等級調價限制 (FREE 方案不准加價)")
    void ts006_02_should_ThrowException_When_FreeSitterTriesToAdjustPrice() {
        // 1. Given: 保母等級為 FREE
        subscriptionRepository.save(Subscription.builder().sitter(userRepository.findById(sitterId).orElseThrow()).planTier("FREE").build());

        UUID orderId = bookingService.createBooking(BookingRequest.builder()
                .sitterId(sitterId).ownerId(ownerId).planId(planId)
                .dates(List.of(LocalDate.of(2026, 6, 1)))
                .idempotencyKey("quote-test-2").build());
        Order order = orderRepository.findById(orderId).orElseThrow();

        // 2. When: 嘗試調價 100 元
        QuoteRequest quoteReq = QuoteRequest.builder()
                .adjustmentAmount(100)
                .expectedTotalAmount(600)
                .version(order.getVersion())
                .build();

        // 3. Then: 應拋出 AUTH_PLAN_LIMIT 異常
        assertThatThrownBy(() -> evaluationService.sendQuote(sitterId, orderId, quoteReq))
                .isInstanceOf(AuthPlanLimitException.class)
                .hasMessageContaining("非 PRO 或 ULTIMATE 方案保母不可進行手動調價");
    }
}
