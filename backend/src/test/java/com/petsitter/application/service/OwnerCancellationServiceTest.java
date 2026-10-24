package com.petsitter.application.service;

import com.petsitter.application.dto.BookingItemRequest;
import com.petsitter.application.dto.BookingRequest;
import com.petsitter.application.dto.QuoteRequest;
import com.petsitter.domain.model.ModificationRequest;
import com.petsitter.domain.model.Order;
import com.petsitter.domain.model.OrderLog;
import com.petsitter.domain.model.Profile;
import com.petsitter.domain.model.ServicePlan;
import com.petsitter.domain.model.User;
import com.petsitter.domain.model.Visit;
import com.petsitter.domain.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * fix-batch10 H5：飼主付款前撤單（PRD-006 例外處理「飼主在保母審核中嘗試取消」）。
 * 可撤：PENDING、PENDING_PAYMENT（未上傳憑證且非補款階段）；其餘 409。BOLA、版本過期、與保母接單 / 報價併發。
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("local")
@WithMockUser(roles = "SITTER")
@DisplayName("TS-006 H5: 飼主付款前撤單")
class OwnerCancellationServiceTest {

    static {
        System.setProperty("com.github.dockerjava.api.version", "1.44");
        System.setProperty("testcontainers.ryuk.disabled", "true");
    }

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private OwnerCancellationService ownerCancellationService;
    @Autowired
    private BookingService bookingService;
    @Autowired
    private ConfirmOrderService confirmOrderService;
    @Autowired
    private EvaluationService evaluationService;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private VisitRepository visitRepository;
    @Autowired
    private OrderLogRepository orderLogRepository;
    @Autowired
    private OrderSnapshotRepository orderSnapshotRepository;
    @Autowired
    private ModificationRequestRepository modificationRequestRepository;
    @Autowired
    private NotificationRepository notificationRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ProfileRepository profileRepository;
    @Autowired
    private ServicePlanRepository servicePlanRepository;
    @Autowired
    private SubscriptionRepository subscriptionRepository;

    private UUID sitterId;
    private UUID ownerId;
    private UUID otherOwnerId;
    private UUID planId;
    private int keySeq;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
        orderLogRepository.deleteAll();
        modificationRequestRepository.deleteAll();
        visitRepository.deleteAll();
        orderSnapshotRepository.deleteAll();
        orderRepository.deleteAll();
        profileRepository.deleteAll();
        subscriptionRepository.deleteAll();
        servicePlanRepository.deleteAll();
        userRepository.deleteAll();

        User sitter = User.builder().email("h5-sitter@example.com").passwordHash("hash").role("SITTER").build();
        User owner = User.builder().email("h5-owner@example.com").passwordHash("hash").role("OWNER").build();
        User other = User.builder().email("h5-other@example.com").passwordHash("hash").role("OWNER").build();
        userRepository.saveAllAndFlush(List.of(sitter, owner, other));
        profileRepository.save(Profile.builder()
                .userId(sitter.getId()).type("SITTER").kycStatus("VERIFIED").isOpen(true).build());
        sitterId = sitter.getId();
        ownerId = owner.getId();
        otherOwnerId = other.getId();

        ServicePlan plan = ServicePlan.builder().sitter(sitter).name("H5 餵食").price(500L).build();
        servicePlanRepository.saveAndFlush(plan);
        planId = plan.getId();
    }

    private UUID createPendingOrder() {
        keySeq++;
        return bookingService.createBooking(BookingRequest.builder()
                .sitterId(sitterId).ownerId(ownerId)
                .items(List.of(BookingItemRequest.builder()
                        .planId(planId)
                        .dates(List.of(LocalDate.of(2026, 12, 1).plusDays(keySeq % 20)))
                        .timesPerDay(1).build()))
                .expectedTotalAmount(500)
                .idempotencyKey("h5-" + keySeq).build());
    }

    private Order reload(UUID orderId) {
        return orderRepository.findById(orderId).orElseThrow();
    }

    private int versionOf(UUID orderId) {
        return reload(orderId).getVersion();
    }

    private Order setStatus(UUID orderId, String status) {
        Order order = reload(orderId);
        order.setStatus(status);
        return orderRepository.saveAndFlush(order);
    }

    private List<OrderLog> logsOf(UUID orderId) {
        return orderLogRepository.findAll().stream()
                .filter(l -> l.getOrder().getId().equals(orderId)).toList();
    }

    @Test
    @DisplayName("PENDING（保母評估中）撤單：訂單與行程 CANCELLED、cancelledBy=OWNER、寫日誌、通知保母")
    void should_CancelOrder_When_Pending() {
        UUID orderId = createPendingOrder();
        assertThat(visitRepository.findByOrderId(orderId)).isNotEmpty();
        assertThat(ownerCancellationService.isCancellableByOwner(reload(orderId))).isTrue();

        ownerCancellationService.cancelByOwner(ownerId, orderId, versionOf(orderId));

        Order cancelled = reload(orderId);
        assertThat(cancelled.getStatus()).isEqualTo("CANCELLED");
        assertThat(cancelled.getCancelledBy()).isEqualTo("OWNER");
        assertThat(visitRepository.findByOrderId(orderId)).extracting(Visit::getStatus).containsOnly("CANCELLED");
        assertThat(logsOf(orderId)).anySatisfy(l -> {
            assertThat(l.getActionType()).isEqualTo("ORDER_CANCELLED_BY_OWNER");
            assertThat(l.getOperatorId()).isEqualTo(ownerId.toString());
            assertThat(l.getPayload()).containsEntry("previousStatus", "PENDING");
        });
        // 通知是 AFTER_COMMIT + @Async，等它寫進 DB
        await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(notificationRepository.findAll()).anySatisfy(n -> {
                    assertThat(n.getUserId()).isEqualTo(sitterId);
                    assertThat(n.getTitle()).isEqualTo("飼主已取消預約");
                    assertThat(n.getCategory()).isEqualTo("ORDER_AFFAIR");
                    assertThat(n.getRoleTarget()).isEqualTo("SITTER");
                    assertThat(n.getLinkUrl()).isEqualTo("/sitter/orders");
                }));
    }

    @Test
    @DisplayName("PENDING_PAYMENT 且未上傳憑證、非補款階段：可撤")
    void should_CancelOrder_When_PendingPaymentWithoutProof() {
        UUID orderId = createPendingOrder();
        confirmOrderService.confirmOrder(sitterId, orderId);
        assertThat(reload(orderId).getStatus()).isEqualTo("PENDING_PAYMENT");

        ownerCancellationService.cancelByOwner(ownerId, orderId, versionOf(orderId));

        assertThat(reload(orderId).getStatus()).isEqualTo("CANCELLED");
        assertThat(logsOf(orderId)).anySatisfy(l ->
                assertThat(l.getPayload()).containsEntry("previousStatus", "PENDING_PAYMENT"));
    }

    @Test
    @DisplayName("PENDING_PAYMENT 但已有付款憑證：409，訂單不變")
    void should_Reject_When_PendingPaymentWithProof() {
        UUID orderId = createPendingOrder();
        confirmOrderService.confirmOrder(sitterId, orderId);
        Order order = reload(orderId);
        order.setPaymentProofUrl("payment-proofs/legacy.jpg");
        orderRepository.saveAndFlush(order);

        assertThatThrownBy(() -> ownerCancellationService.cancelByOwner(ownerId, orderId, versionOf(orderId)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已上傳付款憑證")
                .hasMessageContaining("PRD-016");
        assertThat(reload(orderId).getStatus()).isEqualTo("PENDING_PAYMENT");
        assertThat(reload(orderId).getCancelledBy()).isNull();
    }

    @Test
    @DisplayName("訂單變更補款階段（PENDING_PAYMENT + 最新變更 M_DONE 差額 > 0 未核帳）：409，不可當成未付款撤單")
    void should_Reject_When_SupplementPaymentStage() {
        UUID orderId = createPendingOrder();
        Order order = setStatus(orderId, "PENDING_PAYMENT");
        modificationRequestRepository.saveAndFlush(ModificationRequest.builder()
                .order(order)
                .requestedBy("OWNER")
                .payload(new ArrayList<>())
                .dates(List.of())
                .diffAmount(300)
                .newTotalAmount(800)
                .previousStatus("CONFIRMED")
                .status("M_DONE")
                .build());
        assertThat(ownerCancellationService.isCancellableByOwner(reload(orderId))).isFalse();

        assertThatThrownBy(() -> ownerCancellationService.cancelByOwner(ownerId, orderId, versionOf(orderId)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("補款");
        assertThat(reload(orderId).getStatus()).isEqualTo("PENDING_PAYMENT");
        assertThat(visitRepository.findByOrderId(orderId)).extracting(Visit::getStatus).doesNotContain("CANCELLED");
    }

    @ParameterizedTest(name = "{0} → 409，狀態不變")
    @ValueSource(strings = {"PAID", "CONFIRMED", "IN_PROGRESS", "MODIFYING", "REFUND_VERIFY", "DISPUTED",
            "COMPLETED", "CANCELLED"})
    @DisplayName("付款後 / 協商 / 已結束狀態：一律 409")
    void should_Reject_When_StatusNotCancellable(String status) {
        UUID orderId = createPendingOrder();
        setStatus(orderId, status);
        assertThat(ownerCancellationService.isCancellableByOwner(reload(orderId))).isFalse();

        assertThatThrownBy(() -> ownerCancellationService.cancelByOwner(ownerId, orderId, versionOf(orderId)))
                .isInstanceOf(IllegalStateException.class);
        Order after = reload(orderId);
        assertThat(after.getStatus()).isEqualTo(status);
        assertThat(after.getCancelledBy()).isNull();
        assertThat(logsOf(orderId)).noneMatch(l -> "ORDER_CANCELLED_BY_OWNER".equals(l.getActionType()));
    }

    @Test
    @DisplayName("BOLA：別的飼主、該訂單的保母都不能撤單（403），訂單不變")
    void should_Deny_When_NotOrderOwner() {
        UUID orderId = createPendingOrder();
        int version = versionOf(orderId);

        assertThatThrownBy(() -> ownerCancellationService.cancelByOwner(otherOwnerId, orderId, version))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> ownerCancellationService.cancelByOwner(sitterId, orderId, version))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(reload(orderId).getStatus()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("飼主畫面上的版本已過期（保母已接單）：409，訂單維持保母接單後的狀態")
    void should_Reject_When_VersionStale() {
        UUID orderId = createPendingOrder();
        int seenByOwner = versionOf(orderId);
        confirmOrderService.confirmOrder(sitterId, orderId);

        assertThatThrownBy(() -> ownerCancellationService.cancelByOwner(ownerId, orderId, seenByOwner))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThat(reload(orderId).getStatus()).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    @DisplayName("併發：飼主撤單 vs 保母原價接單同時起跑，只成功一方，另一方 409，結果自洽")
    void should_SucceedOnlyOnce_When_OwnerCancelRacesSitterConfirm() throws Exception {
        for (int round = 0; round < 6; round++) {
            UUID orderId = createPendingOrder();
            int version = versionOf(orderId);

            ConcurrentTransitionSupport.Result result = ConcurrentTransitionSupport.runConcurrently(List.of(
                    () -> ownerCancellationService.cancelByOwner(ownerId, orderId, version),
                    () -> confirmOrderService.confirmOrder(sitterId, orderId)));

            assertThat(result.success()).as("第 %d 輪成功數（%s）", round + 1, result.failures()).isEqualTo(1);
            assertThat(result.failureStatuses()).as("第 %d 輪失敗回應（%s）", round + 1, result.failures())
                    .containsExactly(409);
            assertConsistent(orderId);
        }
    }

    @Test
    @DisplayName("併發：飼主撤單 vs 保母報價同時起跑，只成功一方，另一方 409，結果自洽")
    void should_SucceedOnlyOnce_When_OwnerCancelRacesSitterQuote() throws Exception {
        for (int round = 0; round < 6; round++) {
            UUID orderId = createPendingOrder();
            int version = versionOf(orderId);
            String quoteKey = "h5-quote-" + round;

            ConcurrentTransitionSupport.Result result = ConcurrentTransitionSupport.runConcurrently(List.of(
                    () -> ownerCancellationService.cancelByOwner(ownerId, orderId, version),
                    () -> evaluationService.sendQuote(sitterId, orderId, QuoteRequest.builder()
                            .adjustmentAmount(0).expectedTotalAmount(500).version(version).build(), quoteKey)));

            assertThat(result.success()).as("第 %d 輪成功數（%s）", round + 1, result.failures()).isEqualTo(1);
            assertThat(result.failureStatuses()).as("第 %d 輪失敗回應（%s）", round + 1, result.failures())
                    .containsExactly(409);
            assertConsistent(orderId);
        }
    }

    /** 撤單成功 → CANCELLED 且沒有報價快照；保母成功 → PENDING_PAYMENT、有快照、沒有撤單紀錄 */
    private void assertConsistent(UUID orderId) {
        Order order = reload(orderId);
        long snapshots = orderSnapshotRepository.findAll().stream()
                .filter(s -> s.getOrder().getId().equals(orderId)).count();
        boolean cancelLogged = logsOf(orderId).stream()
                .anyMatch(l -> "ORDER_CANCELLED_BY_OWNER".equals(l.getActionType()));
        if ("CANCELLED".equals(order.getStatus())) {
            assertThat(order.getCancelledBy()).isEqualTo("OWNER");
            assertThat(snapshots).isZero();
            assertThat(cancelLogged).isTrue();
        } else {
            assertThat(order.getStatus()).isEqualTo("PENDING_PAYMENT");
            assertThat(order.getCancelledBy()).isNull();
            assertThat(snapshots).isEqualTo(1);
            assertThat(cancelLogged).isFalse();
        }
    }

    @Test
    @DisplayName("訂單詳情 cancellableByOwner 與撤單 API 共用判斷：已付款時間存在的 PENDING_PAYMENT 也不可撤")
    void should_NotBeCancellable_When_PaidAtPresent() {
        UUID orderId = createPendingOrder();
        Order order = setStatus(orderId, "PENDING_PAYMENT");
        order.setPaidAt(OffsetDateTime.now().minusDays(1));
        orderRepository.saveAndFlush(order);

        assertThat(ownerCancellationService.isCancellableByOwner(reload(orderId))).isFalse();
    }
}
