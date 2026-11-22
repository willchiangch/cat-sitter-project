package com.petsitter.application.service.order;

import com.petsitter.application.dto.order.ModificationResponses;
import com.petsitter.application.dto.order.ModificationSummary;
import com.petsitter.domain.model.order.ActorRole;
import com.petsitter.domain.model.order.InitiatedBy;
import com.petsitter.domain.model.order.ModificationContent;
import com.petsitter.domain.model.order.ModificationRequest;
import com.petsitter.domain.model.order.ModificationStatus;
import com.petsitter.domain.model.order.Order;
import com.petsitter.domain.model.order.OrderStatus;
import com.petsitter.domain.model.order.Payment;
import com.petsitter.domain.repository.order.ModificationRequestRepository;
import com.petsitter.domain.repository.order.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** E18 變更歷史、E19 協商頁（SD-ORDER §6.3；PRD-016 §4.6）。 */
@Service
@RequiredArgsConstructor
public class ModificationQueryService {

    private final OrderCommandSupport support;
    private final PaymentQueryService paymentQueryService;
    private final ModificationRequestRepository modificationRequestRepository;
    private final PaymentRepository paymentRepository;
    private final OrderDetailService orderDetailService;
    private final OrderClock clock;

    public List<ModificationSummary> list(UUID orderId) {
        CurrentActor actor = CurrentActor.fromToken();
        return support.readOnly(() -> {
            Order order = support.loadOrder(orderId);
            paymentQueryService.readerRole(order, actor);
            return summaries(order);
        });
    }

    List<ModificationSummary> summaries(Order order) {
        return modificationRequestRepository.findByOrderIdOrderByCreatedAtDescIdDesc(order.getId()).stream()
                .map(m -> new ModificationSummary(m.getId(), m.getType().name(), m.getStatus().name(), m.getInitiatedBy().name(),
                        m.getReason(), m.getNewTotalAmount(), m.getCreatedAt(), m.getEndedAt()))
                .toList();
    }

    public ModificationResponses.Detail detail(UUID orderId, UUID modificationId) {
        CurrentActor actor = CurrentActor.fromToken();
        return support.readOnly(() -> {
            Order order = support.loadOrder(orderId);
            ModificationRequest m = support.loadModification(order, modificationId);
            ActorRole role = paymentQueryService.readerRole(order, actor);
            OffsetDateTime now = clock.now();
            Payment p = paymentRepository.findByModificationRequestId(m.getId()).orElse(null);
            boolean agreed = m.getAgreedAt() != null;
            OffsetDateTime disputeAt = orderDetailService.disputeAvailableAt(m);
            return new ModificationResponses.Detail(m.getId(), m.getType().name(), m.getStatus().name(), m.getInitiatedBy().name(),
                    m.getReason(), content(m.getRequestedContent()), content(m.getQuotedContent()), m.getSystemAmount(),
                    m.getSurchargeAmount(), m.getDiscountAmount(), m.getAdjustmentReason(), m.getNewTotalAmount(),
                    agreed ? m.getAgreedNetPaid() : m.getQuotedNetPaid(), agreed ? m.getAgreedDiff() : m.getQuotedDiff(),
                    m.getExpiresAt(), m.getEndedAt(), m.getRejectReason(), m.getSourceModificationId(),
                    p == null ? null : new ModificationResponses.PaymentRef(p.getId(), p.getDirection().name(), p.getPurpose().name(),
                            p.getAmount(), p.getStatus().name(), p.getDueAt()),
                    disputeAt, allowed(order, m, role, actor.userId(), now));
        });
    }

    private static ModificationResponses.Content content(ModificationContent c) {
        if (c == null) {
            return null;
        }
        return new ModificationResponses.Content(c.removeVisitIds(), c.addItems().stream()
                .map(a -> new ModificationResponses.AddItem(a.date(), a.planId(), a.dailyCount(), a.petIds())).toList());
    }

    /** 依 PRD-016 §4.6；REQUESTED / QUOTED 已過 expires_at → 全部隱藏；DISPUTED / ADMIN → 無。 */
    List<String> allowed(Order order, ModificationRequest m, ActorRole role, UUID userId, OffsetDateTime now) {
        List<String> a = new ArrayList<>();
        if (role == ActorRole.ADMIN || order.getStatus() == OrderStatus.DISPUTED
                || (order.getStatus() != OrderStatus.CONFIRMED && order.getStatus() != OrderStatus.IN_PROGRESS)) {
            return a;
        }
        boolean negotiating = m.getStatus() == ModificationStatus.REQUESTED || m.getStatus() == ModificationStatus.QUOTED;
        if (negotiating && m.getExpiresAt() != null && !now.isBefore(m.getExpiresAt())) {
            return a;
        }
        if (role == ActorRole.SITTER && m.getStatus() == ModificationStatus.REQUESTED) {
            a.add("QUOTE");
            a.add("REJECT");
        }
        if (role == ActorRole.OWNER && m.getStatus() == ModificationStatus.QUOTED) {
            a.add("REJECT");
            a.add("ACCEPT");
        }
        if (negotiating && m.getInitiatedBy() != InitiatedBy.SYSTEM && m.getInitiatedBy().name().equals(role.name())
                && userId.equals(m.getInitiatedByUserId())) {
            a.add("WITHDRAW");
        }
        if (orderDetailService.disputeAvailable(m, now)) {
            a.add("PAYMENT_DISPUTE");
        }
        return a;
    }
}
