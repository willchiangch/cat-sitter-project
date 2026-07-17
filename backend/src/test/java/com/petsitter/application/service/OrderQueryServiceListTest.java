package com.petsitter.application.service;

import com.petsitter.application.dto.OrderSummaryDto;
import com.petsitter.domain.model.Order;
import com.petsitter.domain.model.OrderItem;
import com.petsitter.domain.model.User;
import com.petsitter.domain.repository.OrderRepository;
import com.petsitter.domain.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@ActiveProfiles("local")
@DisplayName("訂單清單查詢與種子測試資料清理測試")
class OrderQueryServiceListTest {

    static {
        System.setProperty("com.github.dockerjava.api.version", "1.44");
        System.setProperty("testcontainers.ryuk.disabled", "true");
    }

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private OrderQueryService orderQueryService;

    @Autowired
    private TestDataCleanupService testDataCleanupService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private UserRepository userRepository;

    private User owner;
    private User sitter;

    @BeforeEach
    void setUp() {
        orderRepository.deleteAll();
        userRepository.deleteAll();

        owner = userRepository.save(
                User.builder().email("owner-" + UUID.randomUUID() + "@test.com")
                        .passwordHash("password").role("OWNER").fullName("愛貓飼主").isDeleted(false).build());

        sitter = userRepository.save(
                User.builder().email("sitter-" + UUID.randomUUID() + "@test.com")
                        .passwordHash("password").role("SITTER").fullName("測試保母").isDeleted(false).build());
    }

    private Order createOrder(User owner, User sitter, String status) {
        OrderItem item = new OrderItem();
        item.setDates(List.of("2026-05-25", "2026-05-27", "2026-05-26"));
        Order order = Order.builder()
                .owner(owner)
                .sitter(sitter)
                .status(status)
                .planId(UUID.randomUUID())
                .items(new ArrayList<>(List.of(item)))
                .totalAmount(2400)
                .build();
        return orderRepository.save(order);
    }

    @Test
    @DisplayName("飼主查詢自己的訂單清單，回傳保母姓名、金額與排序好的日期標籤")
    void getMyOrdersAsOwner_ReturnsSummary() {
        createOrder(owner, sitter, "PENDING_PAYMENT");

        List<OrderSummaryDto> result = orderQueryService.getMyOrdersAsOwner(owner.getId());

        assertThat(result).hasSize(1);
        OrderSummaryDto dto = result.get(0);
        assertThat(dto.getSitterName()).isEqualTo("測試保母");
        assertThat(dto.getOwnerName()).isEqualTo("愛貓飼主");
        assertThat(dto.getTotalAmount()).isEqualTo(2400);
        assertThat(dto.getScheduledDatesLabel()).isEqualTo("2026-05-25 ~ 2026-05-27 (共 3 天)");
    }

    @Test
    @DisplayName("保母查詢自己的訂單清單")
    void getMyOrdersAsSitter_ReturnsSummary() {
        createOrder(owner, sitter, "PAID");

        List<OrderSummaryDto> result = orderQueryService.getMyOrdersAsSitter(sitter.getId());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getOwnerName()).isEqualTo("愛貓飼主");
    }

    @Test
    @DisplayName("保母帳務總覽：只統計指定月份內已結案訂單的總收入，排除爭議中訂單")
    void getSitterLedger_ReturnsCompletedOrdersWithinMonth_ExcludingDisputed() {
        YearMonth targetMonth = YearMonth.of(2026, 7);

        Order completedInMonth = createOrder(owner, sitter, "COMPLETED");
        completedInMonth.setTotalAmount(2000);
        completedInMonth.setCompletedAt(OffsetDateTime.parse("2026-07-15T00:00:00Z"));
        orderRepository.save(completedInMonth);

        Order completedOutsideMonth = createOrder(owner, sitter, "COMPLETED");
        completedOutsideMonth.setTotalAmount(9999);
        completedOutsideMonth.setCompletedAt(OffsetDateTime.parse("2026-06-30T23:59:59Z"));
        orderRepository.save(completedOutsideMonth);

        createOrder(owner, sitter, "DISPUTED");

        var response = orderQueryService.getSitterLedger(sitter.getId(), targetMonth);

        assertThat(response.getYearMonth()).isEqualTo("2026-07");
        assertThat(response.getTotalRevenue()).isEqualTo(2000);
        assertThat(response.getEntries()).hasSize(1);
        assertThat(response.getEntries().get(0).getOrderId()).isEqualTo(completedInMonth.getId());
    }

    @Test
    @DisplayName("清理端點會把種子測試帳號的訂單軟刪除，之後不再出現在清單")
    void cleanupSeedTestOrders_SoftDeletesOrdersAndExcludesFromList() {
        User seedOwner = userRepository.save(
                User.builder().email("owner@test.com").passwordHash("password").role("OWNER")
                        .fullName("種子飼主").isDeleted(false).build());
        createOrder(seedOwner, sitter, "COMPLETED");

        assertThat(orderQueryService.getMyOrdersAsOwner(seedOwner.getId())).hasSize(1);

        int cleaned = testDataCleanupService.cleanupSeedTestOrders();

        assertThat(cleaned).isEqualTo(1);
        assertThat(orderQueryService.getMyOrdersAsOwner(seedOwner.getId())).isEmpty();
    }
}
