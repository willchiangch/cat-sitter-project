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
    private OrderRepository orderRepository;

    @Autowired
    private VisitRepository visitRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ServicePlanRepository servicePlanRepository;

    @Autowired
    private OrderSnapshotRepository orderSnapshotRepository;

    private UUID ownerId;
    private UUID sitterId;
    private UUID planId;

    @BeforeEach
    void setUp() {
        visitRepository.deleteAll();
        orderSnapshotRepository.deleteAll();
        orderRepository.deleteAll();
        servicePlanRepository.deleteAll();
        userRepository.deleteAll();

        User owner = userRepository.save(User.builder().email("owner@test.com").passwordHash("hash").role("OWNER").build());
        User sitter = userRepository.save(User.builder().email("sitter@test.com").passwordHash("hash").role("SITTER").build());
        
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
        
        for (int i = 0; i < concurrentCount; i++) {
            final int index = i;
            futures.add(CompletableFuture.supplyAsync(() -> {
                return bookingService.createBooking(BookingRequest.builder()
                        .ownerId(ownerId)
                        .sitterId(sitterId)
                        .planId(planId)
                        .dates(List.of(LocalDate.of(2026, 6, 1)))
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

    @Test
    @DisplayName("TS-005-02: 併發確認接單 (Advisory Lock) - 應防止超賣，僅有一筆成功轉為 PENDING_PAYMENT")
    void ts005_02_should_PreventOverselling_When_SitterConcurrentConfirm() {
        // 1. Given: 針對同一日期建立兩筆 PENDING 訂單
        UUID order1 = bookingService.createBooking(BookingRequest.builder()
                .ownerId(ownerId).sitterId(sitterId).planId(planId)
                .dates(List.of(LocalDate.of(2026, 6, 1)))
                .idempotencyKey("order-1").build());

        UUID order2 = bookingService.createBooking(BookingRequest.builder()
                .ownerId(ownerId).sitterId(sitterId).planId(planId)
                .dates(List.of(LocalDate.of(2026, 6, 1)))
                .idempotencyKey("order-2").build());

        // 2. When: 保母同時確認這兩筆訂單
        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        CompletableFuture<Void> f1 = CompletableFuture.runAsync(() -> {
            try {
                bookingService.confirmBooking(order1);
                successCount.incrementAndGet();
            } catch (CapacityFullException e) {
                failureCount.incrementAndGet();
            }
        }, executor);

        CompletableFuture<Void> f2 = CompletableFuture.runAsync(() -> {
            try {
                bookingService.confirmBooking(order2);
                successCount.incrementAndGet();
            } catch (CapacityFullException e) {
                failureCount.incrementAndGet();
            }
        }, executor);

        CompletableFuture.allOf(f1, f2).join();
        executor.shutdown();

        // 3. Then: 應該只有一筆成功，另一筆因為 CapacityFullException 失敗
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failureCount.get()).isEqualTo(1);

        long confirmedCount = orderRepository.findAll().stream()
                .filter(o -> "PENDING_PAYMENT".equals(o.getStatus()))
                .count();
        assertThat(confirmedCount).isEqualTo(1);
    }
}
