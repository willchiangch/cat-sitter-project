package com.petsitter.application.service.order;

import com.petsitter.application.dto.order.TestDataRequests;
import com.petsitter.application.exception.OrderException;
import com.petsitter.application.service.fulfill.FulfillTestInspectionQuery;
import com.petsitter.application.service.notify.NotificationQueryForTest;
import com.petsitter.domain.model.order.ModificationRequest;
import com.petsitter.domain.model.order.Order;
import com.petsitter.domain.model.order.Payment;
import com.petsitter.domain.model.order.PaymentSubmission;
import com.petsitter.domain.repository.order.AdminTaskRepository;
import com.petsitter.domain.repository.order.ModificationRequestRepository;
import com.petsitter.domain.repository.order.OrderLogRepository;
import com.petsitter.domain.repository.order.OrderRepository;
import com.petsitter.domain.repository.order.PaymentRepository;
import com.petsitter.domain.repository.order.PaymentSubmissionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * SD-ORDER §10.4 inspect（唯讀、不取鎖、不寫 log）與 shift-time（取訂單鎖、寫 {@code TEST_TIME_SHIFTED}）。
 * 只對 owner 或 sitter 為 {@code @e2e-journey.test} 的訂單回應，其他 404。
 */
@Service
@RequiredArgsConstructor
public class OrderTestDataService {

    private final OrderRepository orderRepository;
    private final OrderLogRepository orderLogRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentSubmissionRepository submissionRepository;
    private final ModificationRequestRepository modificationRequestRepository;
    private final AdminTaskRepository adminTaskRepository;
    private final NotificationQueryForTest notificationQuery;
    private final FulfillTestInspectionQuery fulfillInspection;
    private final OrderCommandSupport support;
    private final OrderLogWriter orderLogWriter;

    private Order testOrder(UUID orderId) {
        Order o = orderRepository.findById(orderId).orElseThrow(OrderException::notFound);
        if (!orderRepository.isTestAccountOrder(orderId)) {
            throw OrderException.notFound();
        }
        return o;
    }

    public Map<String, Object> inspect(UUID orderId) {
        return support.readOnly(() -> {
            Order o = testOrder(orderId);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("orderId", o.getId());
            out.put("status", o.getStatus().name());
            out.put("totalAmount", o.getTotalAmount());
            out.put("completionBasisAt", o.getCompletionBasisAt());
            out.put("orderLogs", orderLogRepository.findByOrderIdOrderByCreatedAtAscIdAsc(orderId).stream().map(l -> {
                Map<String, Object> m = new HashMap<>();
                m.put("actionType", l.getActionType());
                m.put("operatorRole", l.getOperatorRole().name());
                m.put("operatorUserId", l.getOperatorUserId());
                m.put("payload", l.getPayload());
                m.put("createdAt", l.getCreatedAt());
                return m;
            }).toList());
            out.put("notifications", notificationQuery.findByRelatedOrderId(orderId));
            out.put("adminTasks", adminTaskRepository.findByOrderIdOrderByCreatedAtAsc(orderId).stream().map(t -> {
                Map<String, Object> m = new HashMap<>();
                m.put("taskId", t.getId());
                m.put("type", t.getType().name());
                m.put("status", t.getStatus().name());
                m.put("resolution", t.getResolution() == null ? null : t.getResolution().name());
                m.put("createdAt", t.getCreatedAt());
                m.put("resolvedAt", t.getResolvedAt());
                return m;
            }).toList());
            out.put("payments", paymentRepository.findByOrderIdOrderByCreatedAtAscIdAsc(orderId).stream().map(p -> {
                Map<String, Object> m = new HashMap<>();
                m.put("paymentId", p.getId());
                m.put("direction", p.getDirection().name());
                m.put("purpose", p.getPurpose().name());
                m.put("amount", p.getAmount());
                m.put("status", p.getStatus().name());
                m.put("remindAt", p.getRemindAt());
                m.put("remindedAt", p.getRemindedAt());
                m.put("escalateAt", p.getEscalateAt());
                m.put("escalatedAt", p.getEscalatedAt());
                m.put("verifiedAt", p.getVerifiedAt());
                m.put("voidedAt", p.getVoidedAt());
                return m;
            }).toList());
            out.put("fulfill", fulfillInspection.inspect(orderId));
            return out;
        });
    }

    public Map<String, Object> shiftTime(UUID orderId, TestDataRequests.ShiftTime req) {
        Order probe = testOrder(orderId);
        if (req == null || req.target() == null || req.minutes() == null) {
            throw OrderException.validation(req == null || req.target() == null ? "target" : "minutes");
        }
        long minutes = req.minutes();
        return support.inLockedTx(probe.getId(), locked -> {
            Order o = locked.entity();
            String target = req.target();
            OffsetDateTime before;
            OffsetDateTime after;
            switch (target) {
                case "REVIEW_EXPIRES_AT" -> {
                    before = o.getReviewExpiresAt();
                    after = before.plusMinutes(minutes);
                    o.setReviewExpiresAt(after);
                }
                case "COMPLETION_BASIS_AT" -> {
                    before = require(o.getCompletionBasisAt(), "target");
                    after = before.plusMinutes(minutes);
                    o.setCompletionBasisAt(after);
                }
                case "PAYMENT_DUE_AT", "ESCALATE_AT", "REMIND_AT" -> {
                    Payment p = support.loadPayment(o, require(req.paymentId(), "paymentId"));
                    before = switch (target) {
                        case "PAYMENT_DUE_AT" -> require(p.getDueAt(), "target");
                        case "ESCALATE_AT" -> require(p.getEscalateAt(), "target");
                        default -> require(p.getRemindAt(), "target");
                    };
                    after = before.plusMinutes(minutes);
                    switch (target) {
                        case "PAYMENT_DUE_AT" -> p.setDueAt(after);
                        case "ESCALATE_AT" -> p.setEscalateAt(after);
                        default -> p.setRemindAt(after);
                    }
                }
                case "MODIFICATION_EXPIRES_AT" -> {
                    ModificationRequest m = support.loadModification(o, require(req.modificationId(), "modificationId"));
                    before = require(m.getExpiresAt(), "target");
                    after = before.plusMinutes(minutes);
                    m.setExpiresAt(after);
                }
                case "SUBMISSION_AT" -> {
                    PaymentSubmission s = submissionRepository.findById(require(req.submissionId(), "submissionId"))
                            .filter(x -> paymentRepository.findById(x.getPaymentId()).map(Payment::getOrderId).filter(o.getId()::equals).isPresent())
                            .orElseThrow(OrderException::notFound);
                    before = s.getSubmittedAt();
                    after = before.plusMinutes(minutes);
                    s.setSubmittedAt(after);
                }
                default -> throw OrderException.validation("target");
            }
            orderLogWriter.write(locked, "TEST_TIME_SHIFTED", Actor.SYSTEM,
                    Map.of("target", target, "minutes", minutes, "before", before.toString(), "after", after.toString()));
            return Map.<String, Object>of("target", target, "before", before, "after", after);
        });
    }

    private static <T> T require(T value, String field) {
        if (value == null) {
            throw OrderException.validation(field);
        }
        return value;
    }
}
