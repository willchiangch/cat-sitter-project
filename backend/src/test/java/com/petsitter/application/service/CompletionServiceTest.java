package com.petsitter.application.service;

import com.petsitter.domain.model.Order;
import com.petsitter.domain.model.User;
import com.petsitter.domain.model.Visit;
import com.petsitter.domain.repository.OrderLogRepository;
import com.petsitter.domain.repository.OrderRepository;
import com.petsitter.domain.repository.UserRepository;
import com.petsitter.domain.repository.VisitRepository;
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
    private OrderLogRepository orderLogRepository;

    private User owner;
    private User sitter;

    @BeforeEach
    void setUp() {
        visitRepository.deleteAll();
        orderRepository.deleteAll();
        userRepository.deleteAll();

        owner = userRepository.save(User.builder().email("owner@test.com").passwordHash("hash").role("OWNER").build());
        sitter = userRepository.save(User.builder().email("sitter@test.com").passwordHash("hash").role("SITTER").build());
    }

    @Test
    @DisplayName("應該正確清理 72 小時未打卡的殭屍行程")
    void should_CloseZombieVisits_Successfully() {
        // Given: 一個 80 小時前的 PENDING 行程
        Order order = createOrder("IN_PROGRESS");
        Visit zombieVisit = visitRepository.save(Visit.builder()
                .order(order)
                .status("PENDING")
                .scheduledAt(OffsetDateTime.now().minusHours(80))
                .build());

        // When
        completionService.triggerAutoCompletion();

        // Then
        Visit updatedVisit = visitRepository.findById(zombieVisit.getId()).orElseThrow();
        assertThat(updatedVisit.getStatus()).isEqualTo("CLOSED_BY_SYSTEM");

        // [Fix] 驗證連鎖反應：如果行程全清了且符合時間條件，Order 應該也要轉 COMPLETED
        Order updatedOrder = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(updatedOrder.getStatus()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("應該自動結案最後行程結束過 48 小時的訂單")
    void should_AutoCompleteOrders_After48Hours() {
        // Given: 一個所有行程皆已 DONE 且最後行程是 50 小時前的訂單
        Order order = createOrder("IN_PROGRESS");
        visitRepository.save(Visit.builder()
                .order(order)
                .status("DONE")
                .scheduledAt(OffsetDateTime.now().minusHours(50))
                .build());

        // When
        completionService.triggerAutoCompletion();

        // Then
        Order updatedOrder = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(updatedOrder.getStatus()).isEqualTo("COMPLETED");
        assertThat(updatedOrder.getCompletedAt()).isNotNull();
        assertThat(updatedOrder.getPayoutAt()).isEqualTo(updatedOrder.getCompletedAt().plusDays(3));
    }

    @Test
    @DisplayName("管理員強制結案：應紀錄差額、寫入財務時間並轉為 COMPLETED")
    @WithMockUser(username = "admin_user", roles = "ADMIN")
    void should_ResolveDisputedOrder_ByAdmin() {
        // Given: 一張爭議中的訂單
        Order order = createOrder("DISPUTED");
        order.setTotalAmount(1500);
        order.setDisputed(true);
        orderRepository.save(order);

        // When: 管理員裁決
        com.petsitter.application.dto.AdminResolveRequest req = new com.petsitter.application.dto.AdminResolveRequest(
                1000, 
                "gs://bucket/receipt.jpg", 
                "雙方和解，扣除一天費用"
        );
        completionService.resolveDisputedOrder(order.getId(), req);

        // Then
        Order resolvedOrder = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(resolvedOrder.getStatus()).isEqualTo("COMPLETED");
        assertThat(resolvedOrder.getTotalAmount()).isEqualTo(1000);
        assertThat(resolvedOrder.isDisputed()).isFalse();
        
        // 驗證審計日誌
        long logCount = orderLogRepository.countByOrderIdAndActionType(order.getId(), "ADMIN_RESOLVED");
        assertThat(logCount).isEqualTo(1);
    }

    private Order createOrder(String status) {
        return orderRepository.save(Order.builder()
                .owner(owner)
                .sitter(sitter)
                .planId(UUID.randomUUID())
                .status(status)
                .items(java.util.List.of(new com.petsitter.domain.model.OrderItem("CAT_SITTING", "Standard", 500, 1)))
                .build());
    }
}
