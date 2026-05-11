package com.petsitter.application.service;

import com.petsitter.application.dto.BookingRequest;
import com.petsitter.application.exception.CapacityFullException;
import com.petsitter.domain.model.ServicePlan;
import com.petsitter.domain.model.User;
import com.petsitter.domain.repository.ServicePlanRepository;
import com.petsitter.domain.repository.UserRepository;
import com.petsitter.domain.repository.VisitRepository;
import com.petsitter.domain.repository.OrderRepository;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
@SpringBootTest
@Testcontainers
@Tag("TS-005")
@DisplayName("TS-005: 公開預約引擎測試 (媒合式模型驗證)")
class BookingServiceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private BookingService bookingService;

    @Autowired
    private ConfirmOrderService confirmOrderService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ServicePlanRepository servicePlanRepository;

    @Autowired
    private VisitRepository visitRepository;

    @Autowired
    private OrderRepository orderRepository;

    private UUID sitterId;
    private UUID ownerIdA;
    private UUID ownerIdB;
    private UUID planId;

    @BeforeEach
    void setUp() {
        visitRepository.deleteAll();
        orderRepository.deleteAll();
        servicePlanRepository.deleteAll();
        userRepository.deleteAll();
        
        User sitter = User.builder().email("sitter@example.com").passwordHash("hash").role("SITTER").build();
        User ownerA = User.builder().email("ownerA@example.com").passwordHash("hash").role("OWNER").build();
        User ownerB = User.builder().email("ownerB@example.com").passwordHash("hash").role("OWNER").build();
        userRepository.saveAllAndFlush(List.of(sitter, ownerA, ownerB));
        
        ServicePlan plan = ServicePlan.builder()
                .sitter(sitter)
                .name("專業餵食")
                .dailyCapacity(1) // 配額僅為 1
                .price(500L)
                .build();
        servicePlanRepository.saveAndFlush(plan);
        
        this.sitterId = sitter.getId();
        this.ownerIdA = ownerA.getId();
        this.ownerIdB = ownerB.getId();
        this.planId = plan.getId();
    }

    @Test
    @DisplayName("TS-005-01: 多人同時送單 (媒合式：皆應成功進入 PENDING)")
    void ts005_01_should_AllowMultiplePendingOrders_When_ConcurrentSubmission() throws InterruptedException {
        BookingRequest requestA = BookingRequest.builder()
                .sitterId(sitterId).ownerId(ownerIdA).planId(planId)
                .dates(List.of(LocalDate.of(2026, 6, 1)))
                .idempotencyKey("idemp-A").build();
        
        BookingRequest requestB = BookingRequest.builder()
                .sitterId(sitterId).ownerId(ownerIdB).planId(planId)
                .dates(List.of(LocalDate.of(2026, 6, 1)))
                .idempotencyKey("idemp-B").build();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        executor.submit(() -> bookingService.createBooking(requestA));
        executor.submit(() -> bookingService.createBooking(requestB));
        
        executor.shutdown();
        while(!executor.isTerminated()) { Thread.sleep(10); }

        long pendingCount = orderRepository.findAll().stream()
                .filter(o -> "PENDING".equals(o.getStatus()))
                .count();

        assertThat(pendingCount).as("兩筆訂單都應該成功建立為 PENDING").isEqualTo(2);
    }

    @Test
    @DisplayName("TS-005-02: 保母併發接單 (配額鎖定：僅一筆成功)")
    void ts005_02_should_PreventOverselling_When_SitterConcurrentConfirm() throws InterruptedException {
        // Given: 先建立兩筆 PENDING 訂單
        UUID orderIdA = bookingService.createBooking(BookingRequest.builder()
                .sitterId(sitterId).ownerId(ownerIdA).planId(planId)
                .dates(List.of(LocalDate.of(2026, 6, 1)))
                .idempotencyKey("idemp-A").build());
        
        UUID orderIdB = bookingService.createBooking(BookingRequest.builder()
                .sitterId(sitterId).ownerId(ownerIdB).planId(planId)
                .dates(List.of(LocalDate.of(2026, 6, 1)))
                .idempotencyKey("idemp-B").build());

        // When: 保母「同時」點擊接單
        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        executor.submit(() -> {
            try {
                latch.await();
                confirmOrderService.confirmOrder(sitterId, orderIdA);
                successCount.incrementAndGet();
            } catch (CapacityFullException e) {
                failCount.incrementAndGet();
            } catch (Exception e) {
                log.error("Unexpected error", e);
            } finally {
                doneLatch.countDown();
            }
        });

        executor.submit(() -> {
            try {
                latch.await();
                confirmOrderService.confirmOrder(sitterId, orderIdB);
                successCount.incrementAndGet();
            } catch (CapacityFullException e) {
                failCount.incrementAndGet();
            } catch (Exception e) {
                log.error("Unexpected error", e);
            } finally {
                doneLatch.countDown();
            }
        });

        latch.countDown();
        doneLatch.await();

        // Then: 只有一筆會成功進入 PENDING_PAYMENT，另一筆因配額滿失敗
        log.info("Final counts - Success: {}, Fail: {}", successCount.get(), failCount.get());
        assertThat(successCount.get()).as("保母只能接下一單 (Capacity=1)").isEqualTo(1);
        assertThat(failCount.get()).as("另一筆應因配額滿而被擋下").isEqualTo(1);
    }
}
