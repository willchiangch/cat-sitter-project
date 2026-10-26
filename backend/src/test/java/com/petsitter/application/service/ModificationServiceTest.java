package com.petsitter.application.service;

import com.petsitter.application.dto.ModificationPayloadDto;
import com.petsitter.domain.model.*;
import com.petsitter.domain.repository.*;
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

        // When
        modificationService.confirmModification(ownerId, orderId, modReqId, -500);

        // Then
        assertThat(order.getStatus()).isEqualTo("REFUND_VERIFY");
        assertThat(order.getTotalAmount()).isEqualTo(500);
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

    @Test
    @DisplayName("迴歸測試：飼主確認變更後（REFUND_VERIFY），保母仍應查得到已轉終態的變更請求以便上傳退款憑證")
    void should_ReturnModificationRequest_When_OrderIsRefundVerifyAfterConfirm() {
        // confirmModification() 成功後 modReq.status 會被設為終態 M_DONE，
        // 過去 getActiveModificationRequest() 只認 PENDING_REVIEW/QUOTED，
        // 保母端頁面（含退款憑證上傳表單）重新整理就會查無資料 404，
        // 事實上永遠上傳不了退款憑證
        order.setStatus("REFUND_VERIFY");
        UUID modReqId = UUID.randomUUID();
        ModificationRequest modReq = ModificationRequest.builder()
                .id(modReqId).order(order).status("M_DONE").diffAmount(-500)
                .newTotalAmount(500).payload(new ArrayList<>()).dates(List.of()).build();

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(modRepo.findByOrderIdOrderByCreatedAtDesc(orderId)).thenReturn(List.of(modReq));

        var dto = modificationService.getActiveModificationRequest(sitterId, orderId).orElseThrow();

        assertThat(dto.getId()).isEqualTo(modReqId);
        assertThat(dto.getDiffAmount()).isEqualTo(-500);
        verify(modRepo, never()).findByOrderIdAndStatusIn(any(), any());
    }

    @Test
    @DisplayName("訂單本身無任何變更協商關聯狀態時，查詢變更請求應回空（controller 回 204）而非誤傳歷史資料")
    void should_ReturnEmpty_When_OrderHasNoNegotiationRelatedStatus() {
        order.setStatus("CONFIRMED");

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(modRepo.findByOrderIdAndStatusIn(orderId, List.of("PENDING_REVIEW", "QUOTED")))
                .thenReturn(Optional.empty());

        // N8：沒有進行中的協商是正常終態，不再丟 IllegalStateException（→ 409，前端只能當錯誤紅字顯示）
        assertThat(modificationService.getActiveModificationRequest(sitterId, orderId)).isEmpty();

        verify(modRepo, never()).findByOrderIdOrderByCreatedAtDesc(any());
    }

    // ===== C3 迴歸：退款確認 / 無差額變更過去寫死 CONFIRMED，應依 previousStatus 復原 (PRD-016 C.3) =====

    private ModificationRequest quotedNoDiff(String previousStatus) {
        return ModificationRequest.builder()
                .id(UUID.randomUUID()).order(order).status("QUOTED").diffAmount(0)
                .previousStatus(previousStatus)
                .payload(new ArrayList<>(List.of(new OrderItem())))
                .dates(List.of()).build();
    }

    @Test
    @DisplayName("迴歸測試：無差額變更 — 進行中 (IN_PROGRESS) 發起的變更確認後應回到 IN_PROGRESS，不得退回 CONFIRMED")
    void should_RestoreInProgress_When_NoDiffModificationConfirmed() {
        order.setStatus("MODIFYING");
        ModificationRequest modReq = quotedNoDiff("IN_PROGRESS");
        when(modRepo.findById(modReq.getId())).thenReturn(Optional.of(modReq));
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(visitRepository.findByOrderId(orderId)).thenReturn(new ArrayList<>());

        modificationService.confirmModification(ownerId, orderId, modReq.getId(), 0);

        assertThat(order.getStatus()).isEqualTo("IN_PROGRESS");
        assertThat(modReq.getStatus()).isEqualTo("M_DONE");
    }

    @Test
    @DisplayName("迴歸測試：無差額變更 — 舊資料沒有 previousStatus 快照時 fallback CONFIRMED")
    void should_FallbackToConfirmed_When_NoDiffAndPreviousStatusMissing() {
        order.setStatus("MODIFYING");
        ModificationRequest modReq = quotedNoDiff(null);
        when(modRepo.findById(modReq.getId())).thenReturn(Optional.of(modReq));
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(visitRepository.findByOrderId(orderId)).thenReturn(new ArrayList<>());

        modificationService.confirmModification(ownerId, orderId, modReq.getId(), 0);

        assertThat(order.getStatus()).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("迴歸測試：退款確認 — 進行中 (IN_PROGRESS) 發起的變更，飼主確認收到退款後應回到 IN_PROGRESS")
    void should_RestoreInProgress_When_RefundConfirmed() {
        order.setStatus("REFUND_VERIFY");
        order.setItems(new ArrayList<>(List.of(new OrderItem())));
        ModificationRequest modReq = ModificationRequest.builder()
                .id(UUID.randomUUID()).order(order).status("M_DONE").diffAmount(-500)
                .previousStatus("IN_PROGRESS").payload(new ArrayList<>()).build();
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(modRepo.findByOrderIdOrderByCreatedAtDesc(orderId)).thenReturn(List.of(modReq));

        modificationService.confirmRefund(orderId, ownerId);

        assertThat(order.getStatus()).isEqualTo("IN_PROGRESS");
        verify(orderRepository).saveAndFlush(order);
    }

    @Test
    @DisplayName("迴歸測試：退款確認 — CONFIRMED 發起的變更回到 CONFIRMED；查無變更請求（舊資料）fallback CONFIRMED")
    void should_RestoreConfirmed_When_RefundConfirmedFromConfirmedOrLegacy() {
        order.setStatus("REFUND_VERIFY");
        order.setItems(new ArrayList<>(List.of(new OrderItem())));
        ModificationRequest modReq = ModificationRequest.builder()
                .id(UUID.randomUUID()).order(order).status("M_DONE").diffAmount(-500)
                .previousStatus("CONFIRMED").payload(new ArrayList<>()).build();
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(modRepo.findByOrderIdOrderByCreatedAtDesc(orderId)).thenReturn(List.of(modReq));

        modificationService.confirmRefund(orderId, ownerId);
        assertThat(order.getStatus()).isEqualTo("CONFIRMED");

        order.setStatus("REFUND_VERIFY");
        when(modRepo.findByOrderIdOrderByCreatedAtDesc(orderId)).thenReturn(List.of());
        modificationService.confirmRefund(orderId, ownerId);
        assertThat(order.getStatus()).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("退款確認 — 整筆取消（變更後無預約項目）仍轉 CANCELLED，不受 previousStatus 影響")
    void should_Cancel_When_RefundConfirmedForWholeCancellation() {
        order.setStatus("REFUND_VERIFY");
        order.setItems(new ArrayList<>());
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        modificationService.confirmRefund(orderId, ownerId);

        assertThat(order.getStatus()).isEqualTo("CANCELLED");
    }

    // ===== D2 迴歸：無差額 + 整筆取消應轉 CANCELLED (PRD-016 AC-4)，過去回 previousStatus =====

    @Test
    @DisplayName("迴歸測試：無差額變更 — 整筆取消（變更後無預約項目、保母同意不退款）確認後應轉 CANCELLED，不得回到 previousStatus")
    void should_Cancel_When_NoDiffModificationIsWholeCancellation() {
        order.setStatus("MODIFYING");
        order.setItems(new ArrayList<>(List.of(new OrderItem())));
        ModificationRequest modReq = ModificationRequest.builder()
                .id(UUID.randomUUID()).order(order).status("QUOTED").diffAmount(0)
                .previousStatus("IN_PROGRESS")
                .payload(new ArrayList<>())
                .dates(List.of()).build();
        when(modRepo.findById(modReq.getId())).thenReturn(Optional.of(modReq));
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(visitRepository.findByOrderId(orderId)).thenReturn(new ArrayList<>());

        modificationService.confirmModification(ownerId, orderId, modReq.getId(), 0);

        assertThat(order.getItems()).isEmpty();
        assertThat(order.getTotalAmount()).isEqualTo(1000);
        assertThat(order.getStatus()).isEqualTo("CANCELLED");
        assertThat(modReq.getStatus()).isEqualTo("M_DONE");
    }

    @Test
    @DisplayName("isWholeCancellation：items 為 null 或空才算整筆取消")
    void should_DetectWholeCancellation_OnlyWhenNoItemsLeft() {
        order.setItems(null);
        assertThat(ModificationService.isWholeCancellation(order)).isTrue();
        order.setItems(new ArrayList<>());
        assertThat(ModificationService.isWholeCancellation(order)).isTrue();
        order.setItems(new ArrayList<>(List.of(new OrderItem())));
        assertThat(ModificationService.isWholeCancellation(order)).isFalse();
    }

    // ---- L1（驗收 20261026-1517）：變更新增日期須落在方案可預訂區間，後端權威檢查 ----

    private ServicePlan planWithRange(LocalDate start, LocalDate end) {
        return ServicePlan.builder().id(UUID.randomUUID()).name("暑假限定").price(500L)
                .startDate(start).endDate(end).build();
    }

    /** 原訂單：方案 plan 的 2026-07-10、07-11 兩天 */
    private void givenOrderOnPlan(ServicePlan plan, String... dates) {
        OrderItem item = new OrderItem("BOOKING", plan.getName(), 500, dates.length, plan.getId(),
                new ArrayList<>(List.of(dates)), 1, new ArrayList<>());
        order.setItems(new ArrayList<>(List.of(item)));
        order.setPlanId(plan.getId());
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
    }

    private ModificationPayloadDto proposalOn(UUID planId, String... dates) {
        OrderItem item = new OrderItem("BOOKING", "暑假限定", 500, dates.length, planId,
                new ArrayList<>(List.of(dates)), 1, new ArrayList<>());
        return ModificationPayloadDto.builder()
                .items(new ArrayList<>(List.of(item)))
                .dates(java.util.Arrays.stream(dates).map(LocalDate::parse).toList())
                .totalDays(dates.length)
                .build();
    }

    @Test
    @DisplayName("L1：新增日期晚於方案 endDate → 422 PLAN_NOT_IN_RANGE，不建立變更請求、訂單狀態不變")
    void should_RejectAddedDate_When_AfterPlanEndDate() {
        ServicePlan plan = planWithRange(LocalDate.parse("2026-07-01"), LocalDate.parse("2026-08-31"));
        givenOrderOnPlan(plan, "2026-07-10", "2026-07-11");
        when(servicePlanRepository.findById(plan.getId())).thenReturn(Optional.of(plan));

        var ex = org.junit.jupiter.api.Assertions.assertThrows(
                com.petsitter.application.exception.ServicePlanException.class,
                () -> modificationService.proposeModification(ownerId, orderId,
                        proposalOn(plan.getId(), "2026-07-10", "2026-07-11", "2026-09-01"), "OWNER"));

        assertThat(ex.getError()).isEqualTo("PLAN_NOT_IN_RANGE");
        assertThat(ex.getStatus()).isEqualTo(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(ex.getMessage()).contains("2026-09-01");
        verify(modRepo, never()).save(any());
        assertThat(order.getStatus()).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("L1：新增日期早於方案 startDate → 422 PLAN_NOT_IN_RANGE")
    void should_RejectAddedDate_When_BeforePlanStartDate() {
        ServicePlan plan = planWithRange(LocalDate.parse("2026-07-01"), null);
        givenOrderOnPlan(plan, "2026-07-10");
        when(servicePlanRepository.findById(plan.getId())).thenReturn(Optional.of(plan));

        var ex = org.junit.jupiter.api.Assertions.assertThrows(
                com.petsitter.application.exception.ServicePlanException.class,
                () -> modificationService.proposeModification(ownerId, orderId,
                        proposalOn(plan.getId(), "2026-06-30", "2026-07-10"), "OWNER"));

        assertThat(ex.getError()).isEqualTo("PLAN_NOT_IN_RANGE");
        verify(modRepo, never()).save(any());
    }

    @Test
    @DisplayName("L1：常態開放方案（無區間）任何未來日期都可變更——舊版前端寫死 2026-05 會擋下的日期")
    void should_AcceptAnyDate_When_PlanHasNoRange() {
        ServicePlan plan = planWithRange(null, null);
        givenOrderOnPlan(plan, "2026-10-27");
        when(servicePlanRepository.findById(plan.getId())).thenReturn(Optional.of(plan));
        when(snapshotRepo.findByOrderId(orderId)).thenReturn(Optional.of(snapshot));

        modificationService.proposeModification(ownerId, orderId,
                proposalOn(plan.getId(), "2026-10-27", "2026-10-28"), "OWNER");

        ArgumentCaptor<ModificationRequest> captor = ArgumentCaptor.forClass(ModificationRequest.class);
        verify(modRepo).save(captor.capture());
        assertThat(captor.getValue().getDates()).containsExactly("2026-10-27", "2026-10-28");
        assertThat(order.getStatus()).isEqualTo("MODIFYING");
    }

    @Test
    @DisplayName("L1：區間內的新增日期可變更（含 startDate / endDate 兩端點）")
    void should_AcceptAddedDates_When_WithinRangeInclusive() {
        ServicePlan plan = planWithRange(LocalDate.parse("2026-07-01"), LocalDate.parse("2026-08-31"));
        givenOrderOnPlan(plan, "2026-07-10");
        when(servicePlanRepository.findById(plan.getId())).thenReturn(Optional.of(plan));
        when(snapshotRepo.findByOrderId(orderId)).thenReturn(Optional.of(snapshot));

        modificationService.proposeModification(ownerId, orderId,
                proposalOn(plan.getId(), "2026-07-01", "2026-07-10", "2026-08-31"), "OWNER");

        verify(modRepo).save(any(ModificationRequest.class));
    }

    @Test
    @DisplayName("L1：原訂單已有的日期不受方案區間事後縮短影響——減少天數仍可送出")
    void should_NotRecheckOriginalDates_When_PlanRangeShrunkAfterBooking() {
        // 成交時方案到 8/31，之後保母把區間縮到 7/15；原訂單 7/20、7/21 減為 7/20
        ServicePlan plan = planWithRange(LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-15"));
        givenOrderOnPlan(plan, "2026-07-20", "2026-07-21");
        when(snapshotRepo.findByOrderId(orderId)).thenReturn(Optional.of(snapshot));

        modificationService.proposeModification(ownerId, orderId, proposalOn(plan.getId(), "2026-07-20"), "OWNER");

        verify(modRepo).save(any(ModificationRequest.class));
        // 沒有新增日期就不必查方案
        verify(servicePlanRepository, never()).findById(any());
    }

    @Test
    @DisplayName("L1：以原訂單的方案判斷，不信任 payload 竄改的 planId")
    void should_CheckOriginalOrderPlan_NotPayloadPlanId() {
        ServicePlan plan = planWithRange(LocalDate.parse("2026-07-01"), LocalDate.parse("2026-08-31"));
        givenOrderOnPlan(plan, "2026-07-10");
        when(servicePlanRepository.findById(plan.getId())).thenReturn(Optional.of(plan));
        UUID tamperedPlanId = UUID.randomUUID();

        org.junit.jupiter.api.Assertions.assertThrows(
                com.petsitter.application.exception.ServicePlanException.class,
                () -> modificationService.proposeModification(ownerId, orderId,
                        proposalOn(tamperedPlanId, "2026-07-10", "2026-12-25"), "OWNER"));

        verify(servicePlanRepository, never()).findById(tamperedPlanId);
        verify(modRepo, never()).save(any());
    }

    @Test
    @DisplayName("L1：payload items 內的日期格式錯誤 → IllegalArgumentException（400），不是 500")
    void should_RejectMalformedItemDate() {
        ServicePlan plan = planWithRange(null, null);
        givenOrderOnPlan(plan, "2026-07-10");
        ModificationPayloadDto prop = proposalOn(plan.getId(), "2026-07-10");
        prop.getItems().get(0).setDates(new ArrayList<>(List.of("2026/07/11")));

        var ex = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> modificationService.proposeModification(ownerId, orderId, prop, "OWNER"));
        assertThat(ex.getMessage()).contains("2026/07/11");
        verify(modRepo, never()).save(any());
    }

    @Test
    @DisplayName("L1：ServicePlan.isBookableOn 兩端點含、單邊不限、無區間常態開放")
    void should_EvaluatePlanBookableRange() {
        LocalDate s = LocalDate.parse("2026-07-01");
        LocalDate e = LocalDate.parse("2026-08-31");
        ServicePlan both = planWithRange(s, e);
        assertThat(both.isBookableOn(s)).isTrue();
        assertThat(both.isBookableOn(e)).isTrue();
        assertThat(both.isBookableOn(s.minusDays(1))).isFalse();
        assertThat(both.isBookableOn(e.plusDays(1))).isFalse();
        assertThat(planWithRange(null, e).isBookableOn(LocalDate.parse("2000-01-01"))).isTrue();
        assertThat(planWithRange(s, null).isBookableOn(LocalDate.parse("2099-12-31"))).isTrue();
        assertThat(planWithRange(null, null).isBookableOn(LocalDate.parse("2099-12-31"))).isTrue();
    }
}
