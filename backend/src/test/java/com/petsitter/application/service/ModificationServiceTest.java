package com.petsitter.application.service;

import com.petsitter.application.dto.ModificationPayloadDto;
import com.petsitter.domain.model.*;
import com.petsitter.domain.repository.*;
import com.petsitter.infrastructure.lock.AdvisoryLockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TS-016: 訂單變更單元測試 (Mock 模式)")
class ModificationServiceTest {

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private ModificationRequestRepository modRepo;
    @Mock
    private OrderSnapshotRepository snapshotRepo;
    @Mock
    private VisitRepository visitRepository;
    @Mock
    private ServicePlanRepository servicePlanRepository;
    @Mock
    private AdvisoryLockService lockService;

    @InjectMocks
    private ModificationService modificationService;

    private Order order;
    private OrderSnapshot snapshot;
    private UUID orderId;
    private UUID ownerId;
    private UUID sitterId;

    @BeforeEach
    void setUp() {
        orderId = UUID.randomUUID();
        ownerId = UUID.randomUUID();
        sitterId = UUID.randomUUID();
        User owner = User.builder().id(ownerId).build();
        User sitter = User.builder().id(sitterId).build();
        order = Order.builder()
                .id(orderId)
                .status("CONFIRMED")
                .totalAmount(1000) // 原價 2 天
                .owner(owner)
                .sitter(sitter)
                .planId(UUID.randomUUID())
                .items(new ArrayList<>())
                .build();

        snapshot = OrderSnapshot.builder()
                .order(order)
                .snapshotUnitPrice(500)
                .build();
    }

    @Test
    @DisplayName("應該正確計算加價差額並轉為 MODIFYING")
    void should_ProposeModification_Successfully() {
        ModificationPayloadDto prop = ModificationPayloadDto.builder()
                .totalDays(3) // 2 -> 3
                .items(new ArrayList<>())
                .build();

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(snapshotRepo.findByOrderId(orderId)).thenReturn(Optional.of(snapshot));

        modificationService.proposeModification(ownerId, orderId, prop, "OWNER");

        assertThat(order.getStatus()).isEqualTo("MODIFYING");
        ArgumentCaptor<ModificationRequest> captor = ArgumentCaptor.forClass(ModificationRequest.class);
        verify(modRepo).save(captor.capture());
        assertThat(captor.getValue().getDiffAmount()).isEqualTo(500);
    }

    @Test
    @DisplayName("非訂單當事人發起變更應被拒絕")
    void should_RejectProposeModification_When_RequesterIsNotOrderParty() {
        ModificationPayloadDto prop = ModificationPayloadDto.builder()
                .totalDays(3)
                .items(new ArrayList<>())
                .build();

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        UUID stranger = UUID.randomUUID();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> modificationService.proposeModification(stranger, orderId, prop, "OWNER"));
    }

    @Test
    @DisplayName("保母報價後，退款路徑：減少天數應使訂單轉為 REFUND_VERIFY")
    void should_TransitionToRefundVerify_WhenAmountDecreases() {
        // Given: 保母已報價，差額為 -500
        UUID modReqId = UUID.randomUUID();
        ModificationRequest modReq = ModificationRequest.builder()
                .id(modReqId).order(order).status("QUOTED").diffAmount(-500)
                .payload(new ArrayList<>()).dates(List.of(LocalDate.now().toString())).build();

        when(modRepo.findById(modReqId)).thenReturn(Optional.of(modReq));
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(visitRepository.findByOrderId(orderId)).thenReturn(new ArrayList<>());
        when(servicePlanRepository.findById(any())).thenReturn(Optional.of(ServicePlan.builder().dailyCapacity(1).build()));

        // When
        modificationService.confirmModification(ownerId, orderId, modReqId, -500);

        // Then
        assertThat(order.getStatus()).isEqualTo("REFUND_VERIFY");
        assertThat(order.getTotalAmount()).isEqualTo(500);

        // ✅ 驗證確實呼叫了自我排除的配額檢查
        verify(visitRepository, times(1)).countOccupiedCapacityWithSelfExclusion(
                eq(order.getSitter().getId()),
                any(OffsetDateTime.class),
                any(OffsetDateTime.class),
                eq(orderId)
        );
    }

    @Test
    @DisplayName("同意金額與保母最新報價不符時應拒絕確認 (Zero-Trust)")
    void should_RejectConfirm_When_AgreedAmountMismatchesQuote() {
        UUID modReqId = UUID.randomUUID();
        ModificationRequest modReq = ModificationRequest.builder()
                .id(modReqId).order(order).status("QUOTED").diffAmount(-500).payload(new ArrayList<>()).build();

        when(modRepo.findById(modReqId)).thenReturn(Optional.of(modReq));
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        org.junit.jupiter.api.Assertions.assertThrows(
                com.petsitter.application.exception.PricingMismatchException.class,
                () -> modificationService.confirmModification(ownerId, orderId, modReqId, -999));
    }

    @Test
    @DisplayName("尚未經保母報價 (PENDING_REVIEW) 時不可確認")
    void should_RejectConfirm_When_NotYetQuoted() {
        UUID modReqId = UUID.randomUUID();
        ModificationRequest modReq = ModificationRequest.builder()
                .id(modReqId).order(order).status("PENDING_REVIEW").diffAmount(-500).payload(new ArrayList<>()).build();

        when(modRepo.findById(modReqId)).thenReturn(Optional.of(modReq));
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> modificationService.confirmModification(ownerId, orderId, modReqId, -500));
    }

    @Test
    @DisplayName("保母報價：應更新差額並轉為 QUOTED")
    void should_QuoteModification_Successfully() {
        UUID modReqId = UUID.randomUUID();
        ModificationRequest modReq = ModificationRequest.builder()
                .id(modReqId).order(order).status("PENDING_REVIEW").diffAmount(500).payload(new ArrayList<>()).build();
        order.setVersion(1);

        when(modRepo.findById(modReqId)).thenReturn(Optional.of(modReq));
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        modificationService.quoteModification(sitterId, orderId, modReqId, 700, 1);

        assertThat(modReq.getStatus()).isEqualTo("QUOTED");
        assertThat(modReq.getDiffAmount()).isEqualTo(-300); // 700 - 1000
    }

    @Test
    @DisplayName("保母拒絕變更：訂單應恢復為發起變更前的狀態")
    void should_RejectModification_And_RestorePreviousStatus() {
        UUID modReqId = UUID.randomUUID();
        ModificationRequest modReq = ModificationRequest.builder()
                .id(modReqId).order(order).status("PENDING_REVIEW").diffAmount(500)
                .previousStatus("IN_PROGRESS").payload(new ArrayList<>()).build();
        order.setStatus("MODIFYING");

        when(modRepo.findById(modReqId)).thenReturn(Optional.of(modReq));
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        modificationService.rejectModification(sitterId, orderId, modReqId);

        assertThat(modReq.getStatus()).isEqualTo("REJECTED");
        assertThat(order.getStatus()).isEqualTo("IN_PROGRESS");
    }

    @Test
    @DisplayName("確認變更時應僅刪除 PENDING 行程 (防雷校驗)")
    @SuppressWarnings("unchecked")
    void should_OnlyDeletePendingVisits_WhenConfirming() {
        UUID modReqId = UUID.randomUUID();
        ModificationRequest modReq = ModificationRequest.builder()
                .id(modReqId)
                .order(order)
                .status("QUOTED")
                .diffAmount(500)
                .payload(new ArrayList<>())
                .build();

        LocalDate d1 = LocalDate.of(2026, 6, 1);
        modReq.setDates(List.of(d1.toString(), d1.plusDays(1).toString(), d1.plusDays(2).toString()));
        Visit doneVisit = Visit.builder().status("DONE").scheduledAt(d1.atStartOfDay().atOffset(ZoneOffset.UTC)).build();
        Visit pendingVisit = Visit.builder().status("PENDING").scheduledAt(d1.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC)).build();

        when(modRepo.findById(modReqId)).thenReturn(Optional.of(modReq));
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(visitRepository.findByOrderId(orderId)).thenReturn(List.of(doneVisit, pendingVisit));
        when(servicePlanRepository.findById(any())).thenReturn(Optional.of(ServicePlan.builder().dailyCapacity(1).build()));

        modificationService.confirmModification(ownerId, orderId, modReqId, 500);

        ArgumentCaptor<Iterable<Visit>> deleteCaptor = ArgumentCaptor.forClass(Iterable.class);
        verify(visitRepository).deleteAll(deleteCaptor.capture());
        List<Visit> deletedList = (List<Visit>) deleteCaptor.getValue();
        assertThat(deletedList).hasSize(1).contains(pendingVisit);

        ArgumentCaptor<List<Visit>> saveCaptor = ArgumentCaptor.forClass(List.class);
        verify(visitRepository).saveAll(saveCaptor.capture());
        List<Visit> savedList = saveCaptor.getValue();
        assertThat(savedList).hasSize(2);
    }
}