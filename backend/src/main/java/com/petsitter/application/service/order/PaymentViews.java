package com.petsitter.application.service.order;

import com.petsitter.application.dto.order.PaymentView;
import com.petsitter.application.port.PayoutAccountQuery.PayoutAccountSnapshot;
import com.petsitter.domain.model.order.ActorRole;
import com.petsitter.domain.model.order.Order;
import com.petsitter.domain.model.order.OrderStatus;
import com.petsitter.domain.model.order.Payment;
import com.petsitter.domain.model.order.PaymentDirection;
import com.petsitter.domain.model.order.PaymentStatus;
import com.petsitter.domain.model.order.PaymentSubmission;
import com.petsitter.domain.model.order.SubmissionStatus;
import com.petsitter.domain.repository.order.PaymentRepository;
import com.petsitter.domain.repository.order.PaymentSubmissionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * E10 / E2 / E27 的 payment 檢視（SD-ORDER §6.3 E10）：{@code accountSnapshot} 只在 CHARGE 且呼叫者為付款方、收款方或
 * 可讀範圍 ADMIN 時解密回傳；REFUND 一律 null。{@code allowedActions} 只是畫面提示。
 */
@Component
@RequiredArgsConstructor
public class PaymentViews {

    private final PaymentRepository paymentRepository;
    private final PaymentSubmissionRepository submissionRepository;
    private final PayoutSnapshotCipher cipher;

    /** @param viewer OWNER / SITTER / ADMIN */
    public List<PaymentView> list(Order order, ActorRole viewer, boolean includeSnapshot, OffsetDateTime now) {
        List<Payment> payments = paymentRepository.findByOrderIdOrderByCreatedAtAscIdAsc(order.getId());
        if (payments.isEmpty()) {
            return List.of();
        }
        Map<UUID, UUID> current = submissionRepository.findByPaymentIdInAndStatus(
                        payments.stream().map(Payment::getId).toList(), SubmissionStatus.SUBMITTED).stream()
                .collect(Collectors.toMap(PaymentSubmission::getPaymentId, PaymentSubmission::getId, (a, b) -> a));
        List<PaymentView> out = new ArrayList<>();
        for (Payment p : payments) {
            PaymentView.AccountSnapshot snap = null;
            if (includeSnapshot && p.getDirection() == PaymentDirection.CHARGE && p.getPayeeAccountSnapshot() != null) {
                PayoutAccountSnapshot s = cipher.decrypt(p.getPayeeAccountSnapshot(), p.getPayeeAccountKeyVersion(), p.getId());
                snap = new PaymentView.AccountSnapshot(s.bankCode(), s.branch(), s.accountNumber(), s.accountName());
            }
            out.add(new PaymentView(p.getId(), p.getDirection().name(), p.getPurpose().name(), p.getMethod().name(),
                    p.getAmount(), p.getStatus().name(), p.getDueAt(), p.getEscalateAt(), snap, current.get(p.getId()),
                    p.getCreatedAt(), p.getVerifiedAt(), p.getVoidedAt(), allowed(order, p, viewer, now)));
        }
        return out;
    }

    static List<String> allowed(Order order, Payment p, ActorRole viewer, OffsetDateTime now) {
        List<String> a = new ArrayList<>();
        if (viewer == ActorRole.ADMIN || order.getStatus() == OrderStatus.DISPUTED || OrderCommandSupport.isAdminAdjustment(p)) {
            return a;
        }
        if (viewer == OrderCommandSupport.payer(p) && p.getStatus() == PaymentStatus.UNPAID
                && (p.getDueAt() == null || now.isBefore(p.getDueAt()))) {
            a.add("UPLOAD");
        }
        if (viewer == OrderCommandSupport.payee(p) && p.getStatus() == PaymentStatus.SUBMITTED) {
            a.add("VERIFY");
            if (p.getDirection() == PaymentDirection.CHARGE) {
                a.add("REJECT");
            }
        }
        return a;
    }
}
