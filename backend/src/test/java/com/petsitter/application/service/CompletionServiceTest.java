package com.petsitter.application.service;

import com.petsitter.domain.model.Order;
import com.petsitter.domain.model.User;
import com.petsitter.domain.model.Visit;
import com.petsitter.domain.repository.OrderLogRepository;
import com.petsitter.domain.repository.OrderRepository;
import com.petsitter.domain.repository.UserRepository;
import com.petsitter.domain.repository.VisitRepository;
import com.petsitter.domain.repository.SubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@ActiveProfiles("local")
@WithMockUser(roles = "INTERNAL")
@DisplayName("SD-009: 訂單結案服務測試")
class CompletionServiceTest {

    static {
        System.setProperty("com.github.dockerjava.api.version", "1.44");
        System.setProperty("testcontainers.ryuk.disabled", "true");
    }

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private CompletionService completionService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private VisitRepository visitRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private OrderLogRepository orderLogRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private User owner;
    private User sitter;

    @BeforeEach
    void setUp() {
        subscriptionRepository.deleteAll();
        orderLogRepository.deleteAll();
        visitRepository.deleteAll();
        orderRepository.deleteAll();
        userRepository.deleteAll();

        owner = userRepository.save(User.builder().email("owner@test.com").passwordHash("hash").role("OWNER").build());
        sitter = userRepository.save(User.builder().email("sitter@test.com").passwordHash("hash").role("SITTER").build());
    }

    @Test
    @DisplayName("應該正確清理 72 小時未打卡的殭屍行程")
    void should_CloseZombieVisits_Successfully() {
        Order order = createOrder("IN_PROGRESS");
        Visit zombieVisit = visitRepository.save(Visit.builder()
                .order(order)
                .status("PENDING")
                .planId(UUID.randomUUID())
                .snapshotPlanTitle("Zombie Plan")
                .scheduledAt(OffsetDateTime.now().minusHours(80))
                .build());

        completionService.triggerAutoCompletion();

        Visit updatedVisit = visitRepository.findById(zombieVisit.getId()).orElseThrow();
        assertThat(updatedVisit.getStatus()).isEqualTo("CLOSED_BY_SYSTEM");

        Order updatedOrder = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(updatedOrder.getStatus()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("應該自動結案最後行程結束過 48 小時的訂單")
    void should_AutoCompleteOrders_After48Hours() {
        Order order = createOrder("IN_PROGRESS");
        visitRepository.save(Visit.builder()
                .order(order)
                .status("DONE")
                .planId(UUID.randomUUID())
                .snapshotPlanTitle("Done Plan")
                .scheduledAt(OffsetDateTime.now().minusHours(50))
                .build());

        completionService.triggerAutoCompletion();

        Order updatedOrder = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(updatedOrder.getStatus()).isEqualTo("COMPLETED");
        assertThat(updatedOrder.getCompletedAt()).isNotNull();
        assertThat(updatedOrder.getPayoutAt()).isEqualTo(updatedOrder.getCompletedAt().plusDays(3));
    }

    @Test
    @DisplayName("管理員強制結案：應紀錄差額、寫入財務時間並轉為 COMPLETED")
    @WithMockUser(username = "admin@test.com", roles = "ADMIN")
    void should_ResolveDisputedOrder_ByAdmin() {
        userRepository.save(User.builder().email("admin@test.com").passwordHash(passwordEncoder.encode("password")).role("ADMIN").build());

        Order order = createOrder("DISPUTED");
        order.setTotalAmount(1500);
        order.setDisputed(true);
        orderRepository.save(order);

        com.petsitter.application.dto.AdminResolveRequest req = new com.petsitter.application.dto.AdminResolveRequest(
                1000,
                "gs://bucket/receipt.jpg",
                "雙方和解，扣除一天費用",
                "password"
        );
        completionService.resolveDisputedOrder(order.getId(), req);

        Order resolvedOrder = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(resolvedOrder.getStatus()).isEqualTo("COMPLETED");
        assertThat(resolvedOrder.getTotalAmount()).isEqualTo(1000);
        assertThat(resolvedOrder.isDisputed()).isFalse();

        long logCount = orderLogRepository.countByOrderIdAndActionType(order.getId(), "ADMIN_RESOLVED");
        assertThat(logCount).isEqualTo(1);
    }

    @Test
    @DisplayName("管理員強制結案：二次驗證密碼錯誤時應拒絕並維持 DISPUTED")
    @WithMockUser(username = "admin@test.com", roles = "ADMIN")
    void should_RejectResolve_When_AdminPasswordWrong() {
        userRepository.save(User.builder().email("admin@test.com").passwordHash(passwordEncoder.encode("password")).role("ADMIN").build());

        Order order = createOrder("DISPUTED");
        order.setDisputed(true);
        orderRepository.save(order);

        com.petsitter.application.dto.AdminResolveRequest req = new com.petsitter.application.dto.AdminResolveRequest(
                1000,
                "gs://bucket/receipt.jpg",
                "雙方和解，扣除一天費用",
                "wrong-password"
        );

        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.security.access.AccessDeniedException.class,
                () -> completionService.resolveDisputedOrder(order.getId(), req));

        Order untouchedOrder = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(untouchedOrder.getStatus()).isEqualTo("DISPUTED");
    }

    @Test
    @DisplayName("飼主手動結案：訂單沒有任何行程時應拒絕，維持 CONFIRMED 不放款 (驗收 N10)")
    void should_RejectManualComplete_When_OrderHasNoVisits() {
        Order order = createOrder("CONFIRMED");

        IllegalStateException ex = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> completionService.manualComplete(order.getId(), owner.getId()));
        assertThat(ex.getMessage()).contains("沒有任何行程");

        Order untouched = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(untouched.getStatus()).isEqualTo("CONFIRMED");
        assertThat(untouched.getCompletedAt()).isNull();
        assertThat(untouched.getPayoutAt()).isNull();
    }

    @Test
    @DisplayName("飼主手動結案：仍有待執行行程時應拒絕 (PRD-009 A.1)")
    void should_RejectManualComplete_When_VisitStillPending() {
        Order order = createOrder("CONFIRMED");
        visitRepository.save(Visit.builder()
                .order(order)
                .status("PENDING")
                .planId(UUID.randomUUID())
                .snapshotPlanTitle("Pending Plan")
                .scheduledAt(OffsetDateTime.now().plusDays(1))
                .build());

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> completionService.manualComplete(order.getId(), owner.getId()));

        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus()).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("飼主手動結案：所有行程 DONE 時轉為 COMPLETED 並排定撥款")
    void should_ManualComplete_When_AllVisitsDone() {
        Order order = createOrder("IN_PROGRESS");
        visitRepository.save(Visit.builder()
                .order(order)
                .status("DONE")
                .planId(UUID.randomUUID())
                .snapshotPlanTitle("Done Plan")
                .scheduledAt(OffsetDateTime.now().minusHours(2))
                .build());

        completionService.manualComplete(order.getId(), owner.getId());

        Order completed = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo("COMPLETED");
        assertThat(completed.getPayoutAt()).isEqualTo(completed.getCompletedAt().plusDays(3));
    }

    private void addVisit(Order order, String status, OffsetDateTime scheduledAt) {
        visitRepository.save(Visit.builder()
                .order(order)
                .status(status)
                .planId(UUID.randomUUID())
                .snapshotPlanTitle("Plan")
                .scheduledAt(scheduledAt)
                .build());
    }

    // ===== fix-batch10 H2：manualComplete 狀態白名單（只允許 CONFIRMED / IN_PROGRESS） =====

    @ParameterizedTest(name = "{0} → 409，狀態與撥款日期不變")
    @ValueSource(strings = {"PENDING", "PENDING_PAYMENT", "PAID", "MODIFYING", "REFUND_VERIFY",
            "DISPUTED", "CANCELLED", "REJECTED"})
    @DisplayName("飼主手動結案：非 CONFIRMED / IN_PROGRESS 一律拒絕（行程全部 DONE 也不行）")
    void should_RejectManualComplete_When_StatusNotCompletable(String status) {
        Order order = createOrder(status);
        addVisit(order, "DONE", OffsetDateTime.now().minusHours(2));

        IllegalStateException ex = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> completionService.manualComplete(order.getId(), owner.getId()));
        assertThat(ex.getMessage()).isNotBlank();

        Order untouched = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(untouched.getStatus()).isEqualTo(status);
        assertThat(untouched.getCompletedAt()).isNull();
        assertThat(untouched.getPayoutAt()).isNull();
    }

    @ParameterizedTest(name = "{0} 協商中 → 拒絕並提示協商")
    @ValueSource(strings = {"MODIFYING", "REFUND_VERIFY"})
    @DisplayName("飼主手動結案：訂單變更協商中拒絕，訊息提示待協商完成")
    void should_RejectManualComplete_When_Negotiating(String status) {
        Order order = createOrder(status);
        addVisit(order, "DONE", OffsetDateTime.now().minusHours(2));

        IllegalStateException ex = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> completionService.manualComplete(order.getId(), owner.getId()));
        assertThat(ex.getMessage()).contains("協商");
    }

    @Test
    @DisplayName("飼主手動結案：COMPLETED 重複呼叫 → 409，completedAt / payoutAt 不被往後推")
    void should_RejectManualComplete_And_KeepPayoutDate_When_AlreadyCompleted() {
        Order order = createOrder("COMPLETED");
        OffsetDateTime completedAt = OffsetDateTime.now().minusDays(2).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        order.setCompletedAt(completedAt);
        order.setPayoutAt(completedAt.plusDays(3));
        orderRepository.save(order);
        addVisit(order, "DONE", OffsetDateTime.now().minusDays(3));
        Order before = orderRepository.findById(order.getId()).orElseThrow();

        IllegalStateException ex = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> completionService.manualComplete(order.getId(), owner.getId()));
        assertThat(ex.getMessage()).contains("已結案");

        Order after = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("COMPLETED");
        assertThat(after.getCompletedAt()).isEqualTo(before.getCompletedAt());
        assertThat(after.getPayoutAt()).isEqualTo(before.getPayoutAt());
    }

    @Test
    @DisplayName("飼主手動結案：CONFIRMED 且行程全部 DONE / CLOSED_BY_SYSTEM 可結案")
    void should_ManualComplete_When_ConfirmedAndAllVisitsFinished() {
        Order order = createOrder("CONFIRMED");
        addVisit(order, "DONE", OffsetDateTime.now().minusHours(5));
        addVisit(order, "CLOSED_BY_SYSTEM", OffsetDateTime.now().minusHours(80));

        completionService.manualComplete(order.getId(), owner.getId());

        Order completed = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo("COMPLETED");
        assertThat(completed.getPayoutAt()).isEqualTo(completed.getCompletedAt().plusDays(3));
    }

    private Order createOrder(String status) {
        com.petsitter.domain.model.OrderItem orderItem = new com.petsitter.domain.model.OrderItem();
        orderItem.setCategory("CAT_SITTING");
        orderItem.setServiceName("Standard");
        orderItem.setUnitPrice(500);
        orderItem.setQuantity(1);

        return orderRepository.save(Order.builder()
                .owner(owner)
                .sitter(sitter)
                .planId(UUID.randomUUID())
                .status(status)
                .items(java.util.List.of(orderItem))
                .build());
    }
}
