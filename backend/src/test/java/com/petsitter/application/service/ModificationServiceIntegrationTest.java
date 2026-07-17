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
}
