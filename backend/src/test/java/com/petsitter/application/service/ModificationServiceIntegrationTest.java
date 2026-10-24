package com.petsitter.application.service;

import com.petsitter.application.dto.ModificationPayloadDto;
import com.petsitter.domain.model.*;
import com.petsitter.domain.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@ActiveProfiles("local")
@DisplayName("TS-016: 訂單變更整合測試 (併發防禦)")
class ModificationServiceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private ModificationService modificationService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private OrderSnapshotRepository snapshotRepo;

    @Autowired
    private ModificationRequestRepository modRepo;

    private UUID orderId;
    private UUID ownerId;

    @BeforeEach
    void setUp() {
        subscriptionRepository.deleteAll();
        modRepo.deleteAll();
        snapshotRepo.deleteAll();
        orderRepository.deleteAll();
        userRepository.deleteAll();

        User owner = userRepository.save(User.builder().email("owner@test.com").passwordHash("hash").role("OWNER").build());
        User sitter = userRepository.save(User.builder().email("sitter@test.com").passwordHash("hash").role("SITTER").build());
        ownerId = owner.getId();
        
        Order order = orderRepository.save(Order.builder()
                .owner(owner)
                .sitter(sitter)
                .status("CONFIRMED")
                .totalAmount(1000)
                .planId(UUID.randomUUID())
                .items(new ArrayList<>())
                .build());
        
        orderId = order.getId();

        snapshotRepo.save(OrderSnapshot.builder()
                .order(order)
                .snapshotPlanTitle("Standard Plan")
                .snapshotUnitPrice(500)
                .snapshotOriginalTotal(1000)
                .adjustmentAmount(0)
                .mediaRetentionDays(30)
                .maxPhotos(50)
                .maxVideoSeconds(60)
                .snapshotData(new ArrayList<>())
                .build());
    }

    @Test
    @DisplayName("Scenario C: 併發防禦 - 同一訂單不可同時存在兩個 PENDING_REVIEW 的變更請求")
    void should_PreventConcurrentModificationRequests() throws InterruptedException {
        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        ModificationPayloadDto prop = ModificationPayloadDto.builder()
                .totalDays(3)
                .items(new ArrayList<>())
                .build();

        for (int i = 0; i < threadCount; i++) {
            executor.execute(() -> {
                try {
                    latch.await();
                    modificationService.proposeModification(ownerId, orderId, prop, "OWNER");
                    successCount.incrementAndGet();
                } catch (DataIntegrityViolationException | IllegalStateException | org.springframework.orm.ObjectOptimisticLockingFailureException e) {
                    failureCount.incrementAndGet();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }

        latch.countDown();
        Thread.sleep(2000); // 等待非同步執行

        // 驗證：只有一個成功，其餘應因為 Partial Index 衝突而失敗
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failureCount.get()).isEqualTo(threadCount - 1);
        
        long dbCount = modRepo.count();
        assertThat(dbCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Scenario C2: 併發防禦 - 同一變更請求被同時確認兩次，只結轉一次，另一個 409（移除容量 advisory lock 後仍由 version 樂觀鎖保證）")
    void should_ApplyOnce_When_SameModificationConfirmedConcurrently() throws Exception {
        // 2026-10 backlog 第 7 批移除 confirmModification 的保母 + 日期 advisory lock 與名額檢查（系統不做容量控管），
        // 同一張單的重複確認改由 orders / modification_requests 的 @Version 擋下
        Order order = orderRepository.findById(orderId).orElseThrow();
        order.setStatus("MODIFYING");
        orderRepository.save(order);
        ModificationRequest modReq = modRepo.save(ModificationRequest.builder()
                .order(order)
                .requestedBy("OWNER")
                .payload(new ArrayList<>(java.util.List.of(new OrderItem(
                        "CARE", "Standard Plan", 500, 1, UUID.randomUUID(), java.util.List.of(), 1, java.util.List.of()))))
                .dates(java.util.List.of())
                .diffAmount(-200)
                .newTotalAmount(800)
                .previousStatus("CONFIRMED")
                .status("QUOTED")
                .build());

        ConcurrentTransitionSupport.Result result = ConcurrentTransitionSupport.runConcurrently(java.util.List.of(
                () -> modificationService.confirmModification(ownerId, orderId, modReq.getId(), -200),
                () -> modificationService.confirmModification(ownerId, orderId, modReq.getId(), -200)));

        assertThat(result.success()).isEqualTo(1);
        assertThat(result.failureStatuses()).as("失敗回應（%s）", result.failures()).containsExactly(409);
        Order after = orderRepository.findById(orderId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("REFUND_VERIFY");
        assertThat(after.getTotalAmount()).as("差額只套用一次").isEqualTo(800);
        assertThat(modRepo.findById(modReq.getId()).orElseThrow().getStatus()).isEqualTo("M_DONE");
    }
}
