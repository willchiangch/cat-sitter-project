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
                org.springframework.security.authentication.BadCredentialsException.class,
                () -> completionService.resolveDisputedOrder(order.getId(), req));

        Order untouchedOrder = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(untouchedOrder.getStatus()).isEqualTo("DISPUTED");
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
