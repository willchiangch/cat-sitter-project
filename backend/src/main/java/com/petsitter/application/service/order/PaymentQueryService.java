package com.petsitter.application.service.order;

import com.petsitter.application.dto.order.OrderCommandResponses;
import com.petsitter.application.dto.order.PaymentView;
import com.petsitter.application.exception.OrderException;
import com.petsitter.application.exception.StorageUnavailableException;
import com.petsitter.application.service.MediaStorageService;
import com.petsitter.domain.model.order.ActorRole;
import com.petsitter.domain.model.order.Order;
import com.petsitter.domain.model.order.Payment;
import com.petsitter.domain.model.order.PaymentStatus;
import com.petsitter.domain.model.order.PaymentSubmission;
import com.petsitter.domain.model.order.SubmissionStatus;
import com.petsitter.domain.repository.order.PaymentSubmissionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** E10、E12、E13（唯讀；SD-ORDER §6.3）。 */
@Service
@RequiredArgsConstructor
public class PaymentQueryService {

    private final OrderCommandSupport support;
    private final AdminOrderAccessPolicy adminPolicy;
    private final PaymentViews paymentViews;
    private final PaymentSubmissionRepository submissionRepository;
    private final MediaStorageService mediaStorageService;
    private final OrderSettings settings;
    private final OrderClock clock;

    /** G4a / G5（含 ADMIN 可讀範圍）：回傳呼叫者角色。 */
    ActorRole readerRole(Order order, CurrentActor actor) {
        if (actor.isAdminRole()) {
            adminPolicy.requireReadable(order);
            return ActorRole.ADMIN;
        }
        return OrderAccess.partyRole(order, actor);
    }

    public List<PaymentView> list(UUID orderId) {
        CurrentActor actor = CurrentActor.fromToken();
        return support.readOnly(() -> {
            Order order = support.loadOrder(orderId);
            ActorRole role = readerRole(order, actor);
            return paymentViews.list(order, role, true, clock.now());
        });
    }

    public List<OrderCommandResponses.SubmissionItem> submissions(UUID orderId, UUID paymentId) {
        CurrentActor actor = CurrentActor.fromToken();
        return support.readOnly(() -> {
            Order order = support.loadOrder(orderId);
            Payment payment = support.loadPayment(order, paymentId);
            readerRole(order, actor);
            return submissionRepository.findByPaymentIdOrderBySubmittedAtDescIdDesc(payment.getId()).stream()
                    .map(s -> new OrderCommandResponses.SubmissionItem(s.getId(), s.getStatus().name(), s.getLastFive(),
                            s.getSubmittedAt(), s.getDisclaimerAgreedAt(), s.getReviewedAt(),
                            s.getStatus() == SubmissionStatus.ACCEPTED ? s.getReviewedAt() : null, s.getRejectReason(),
                            payment.getStatus() == PaymentStatus.VOID && s.getStatus() == SubmissionStatus.SUBMITTED))
                    .toList();
        });
    }

    /** 簽名在交易外產生（SD-FULFILL §2.5 第 4 點同原則）；失敗 503。 */
    public OrderCommandResponses.ProofUrl proofUrl(UUID orderId, UUID paymentId, UUID submissionId) {
        CurrentActor actor = CurrentActor.fromToken();
        PaymentSubmission submission = support.readOnly(() -> {
            Order order = support.loadOrder(orderId);
            Payment payment = support.loadPayment(order, paymentId);
            PaymentSubmission s = submissionRepository.findById(submissionId)
                    .filter(x -> x.getPaymentId().equals(payment.getId()))
                    .orElseThrow(OrderException::notFound);
            readerRole(order, actor);
            return s;
        });
        OffsetDateTime expiresAt = clock.now().plus(settings.getProofUrlTtl());
        try {
            String url = mediaStorageService.generateSignedUrl(submission.getProofObjectKey(), settings.getProofUrlTtl());
            return new OrderCommandResponses.ProofUrl(url, expiresAt);
        } catch (RuntimeException e) {
            throw new StorageUnavailableException("sign url failed", e);
        }
    }
}
