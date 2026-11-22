package com.petsitter.application.service.fulfill;

import com.petsitter.application.dto.fulfill.FulfillRequests;
import com.petsitter.application.exception.FulfillException;
import com.petsitter.application.service.order.Actor;
import com.petsitter.application.service.order.LockedOrder;
import com.petsitter.application.service.order.OrderClock;
import com.petsitter.application.service.order.OrderLifecycleService;
import com.petsitter.application.service.order.OrderLogWriter;
import com.petsitter.domain.model.fulfill.Visit;
import com.petsitter.domain.model.fulfill.VisitServiceReport;
import com.petsitter.domain.model.order.OrderStatus;
import com.petsitter.domain.model.order.VisitStatus;
import com.petsitter.domain.repository.fulfill.VisitRef;
import com.petsitter.domain.repository.fulfill.VisitRepository;
import com.petsitter.domain.repository.fulfill.VisitServiceReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 測試造資料 FT1 force-status、FT2 shift-time（SD-FULFILL §6.3、§10.2）：只接受 owner 或 sitter 為
 * {@code @e2e-journey.test} 帳號的行程（其他 404）；取訂單鎖後經與正式路徑相同的 {@code onVisitLeftPending} /
 * {@code onVisitFinished}；寫 {@code TEST_VISIT_FORCED} / {@code TEST_TIME_SHIFTED}（非業務 action）；不發通知。
 */
@Service
@RequiredArgsConstructor
public class FulfillTestDataService {

    private static final Set<OrderStatus> SHIFT_ORDER = EnumSet.of(OrderStatus.PENDING_PAYMENT, OrderStatus.CONFIRMED,
            OrderStatus.IN_PROGRESS, OrderStatus.DISPUTED);
    static final String TEST_CARE_LOG_TEXT = "E2E 測試照護日誌";
    /** SERVICE_DATE 整群組平移先移到遠處再移回，避開非 deferrable 的 ux_visits_slot 逐列檢查。 */
    static final int PARK_DAYS = 100_000;

    private final VisitRepository visitRepository;
    private final VisitServiceReportRepository reportRepository;
    private final VisitCommandService visitCommandService;
    private final OrderLifecycleService lifecycleService;
    private final OrderLogWriter orderLogWriter;
    private final FulfillTx tx;
    private final OrderClock clock;

    private VisitRef testVisit(UUID visitId) {
        VisitRef ref = visitRepository.findRef(visitId).orElseThrow(FulfillException::notFound);
        if (!visitRepository.isTestAccountVisit(visitId)) {
            throw FulfillException.notFound();
        }
        return ref;
    }

    // ------------------------------------------------------------------ FT1

    public Map<String, Object> forceStatus(UUID visitId, FulfillRequests.ForceStatus req) {
        VisitRef ref = testVisit(visitId);
        if (req == null || req.status() == null) {
            throw FulfillException.validation("status");
        }
        VisitStatus target;
        try {
            target = VisitStatus.valueOf(req.status());
        } catch (IllegalArgumentException e) {
            throw FulfillException.validation("status");
        }
        if (target == VisitStatus.PENDING) {
            throw FulfillException.validation("status");
        }
        OffsetDateTime startedAt = time(req.startedAt(), "startedAt");
        OffsetDateTime completedAt = time(req.completedAt(), "completedAt");
        OffsetDateTime closedAt = time(req.closedAt(), "closedAt");
        OffsetDateTime cancelledAt = time(req.cancelledAt(), "cancelledAt");
        String source = req.completionSource() == null ? Visit.SOURCE_SITTER : req.completionSource();
        if (!Visit.SOURCE_SITTER.equals(source) && !Visit.SOURCE_SYSTEM.equals(source)) {
            throw FulfillException.validation("completionSource");
        }
        return tx.inLockedTx(ref.orderId(), locked -> {
            OffsetDateTime now = clock.now();
            Visit v = visitRepository.findById(visitId).orElseThrow(FulfillException::notFound);
            OrderStatus os = locked.status();
            boolean orderOk = VisitRules.ACTIVE_ORDER.contains(os)
                    || (target == VisitStatus.CANCELLED && os == OrderStatus.PENDING_PAYMENT);
            if (!orderOk) {
                throw FulfillException.orderState(os);
            }
            VisitStatus from = v.getStatus();
            boolean allowed = from == VisitStatus.PENDING || (from == VisitStatus.IN_PROGRESS && target == VisitStatus.DONE);
            if (!allowed) {
                throw FulfillException.visitState(from, v.getCompletionSource());
            }
            switch (target) {
                case IN_PROGRESS -> {
                    v.setStatus(VisitStatus.IN_PROGRESS);
                    v.setStartedAt(startedAt == null ? now : startedAt);
                    visitRepository.flush();
                    lifecycleService.onVisitLeftPending(locked, visitId, VisitExitCause.STARTED, Actor.SYSTEM);
                }
                case DONE -> {
                    OffsetDateTime done = completedAt == null ? now : completedAt;
                    OffsetDateTime start = from == VisitStatus.IN_PROGRESS ? v.getStartedAt() : (startedAt == null ? done : startedAt);
                    if (start.isAfter(done)) {
                        throw FulfillException.validation("startedAt");
                    }
                    v.setStatus(VisitStatus.DONE);
                    v.setStartedAt(start);
                    v.setCompletedAt(done);
                    v.setCompletionSource(source);
                    visitRepository.flush();
                    if (from == VisitStatus.PENDING) {
                        lifecycleService.onVisitLeftPending(locked, visitId, VisitExitCause.STARTED, Actor.SYSTEM);
                    }
                    if (Boolean.TRUE.equals(req.careLogSubmitted())) {
                        VisitServiceReport r = reportRepository.findByVisitId(visitId).orElse(
                                VisitServiceReport.builder().visitId(visitId).createdBy(v.getSitterId()).build());
                        r.setStatus(VisitServiceReport.SUBMITTED);
                        r.setContent(r.getContent() == null || r.getContent().isBlank() ? TEST_CARE_LOG_TEXT : r.getContent());
                        r.setSubmittedAt(done);
                        r.setUpdatedBy(v.getSitterId());
                        reportRepository.saveAndFlush(r);
                    }
                    lifecycleService.onVisitFinished(locked);
                }
                case CLOSED_BY_SYSTEM -> {
                    v.setStatus(VisitStatus.CLOSED_BY_SYSTEM);
                    v.setClosedAt(closedAt == null ? now : closedAt);
                    visitRepository.flush();
                    lifecycleService.onVisitLeftPending(locked, visitId, VisitExitCause.CLOSED_BY_SYSTEM, Actor.SYSTEM);
                    lifecycleService.onVisitFinished(locked);
                }
                case CANCELLED -> {
                    visitCommandService.cancelVisits(locked, Set.of(visitId), cancelledAt == null ? now : cancelledAt);
                    lifecycleService.onVisitFinished(locked);
                }
                default -> throw FulfillException.validation("status");
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("visitId", visitId.toString());
            payload.put("from", from.name());
            payload.put("to", target.name());
            payload.put("transitions", List.of(OrderLogWriter.transition("VISIT", visitId, from, target)));
            orderLogWriter.write(locked, "TEST_VISIT_FORCED", Actor.SYSTEM, payload);
            return view(visitRepository.findById(visitId).orElseThrow(), locked);
        });
    }

    // ------------------------------------------------------------------ FT2

    public Map<String, Object> shiftTime(UUID visitId, FulfillRequests.ShiftTime req) {
        VisitRef ref = testVisit(visitId);
        if (req == null || req.target() == null) {
            throw FulfillException.validation("target");
        }
        String target = req.target();
        boolean dateShift = "SERVICE_DATE".equals(target);
        if (!dateShift && !Set.of("STARTED_AT", "COMPLETED_AT", "CLOSED_AT").contains(target)) {
            throw FulfillException.validation("target");
        }
        if (dateShift ? req.days() == null : req.minutes() == null) {
            throw FulfillException.validation(dateShift ? "days" : "minutes");
        }
        return tx.inLockedTx(ref.orderId(), locked -> {
            OrderStatus os = locked.status();
            if (!SHIFT_ORDER.contains(os) || (os == OrderStatus.PENDING_PAYMENT && !dateShift)) {
                throw FulfillException.orderState(os);
            }
            Visit v = visitRepository.findById(visitId).orElseThrow(FulfillException::notFound);
            Object before;
            Object after;
            switch (target) {
                case "STARTED_AT" -> {
                    before = require(v.getStartedAt());
                    v.setStartedAt(v.getStartedAt().plusMinutes(req.minutes()));
                    after = v.getStartedAt();
                }
                case "COMPLETED_AT" -> {
                    before = require(v.getCompletedAt());
                    v.setCompletedAt(v.getCompletedAt().plusMinutes(req.minutes()));
                    after = v.getCompletedAt();
                }
                case "CLOSED_AT" -> {
                    before = require(v.getClosedAt());
                    v.setClosedAt(v.getClosedAt().plusMinutes(req.minutes()));
                    after = v.getClosedAt();
                }
                default -> {
                    before = v.getServiceDate();
                    int days = Math.toIntExact(req.days());
                    visitRepository.shiftGroupServiceDate(v.getOrderItemId(), PARK_DAYS + days);
                    visitRepository.shiftGroupServiceDate(v.getOrderItemId(), -PARK_DAYS);
                    after = v.getServiceDate().plusDays(days);
                }
            }
            visitRepository.flush();
            if (!"STARTED_AT".equals(target)) {
                lifecycleService.onVisitFinished(locked);
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("visitId", visitId.toString());
            payload.put("target", target);
            payload.put(dateShift ? "days" : "minutes", dateShift ? req.days() : req.minutes());
            payload.put("before", before.toString());
            payload.put("after", after.toString());
            orderLogWriter.write(locked, "TEST_TIME_SHIFTED", Actor.SYSTEM, payload);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("visitId", visitId);
            out.put("target", target);
            out.put("before", before);
            out.put("after", after);
            return out;
        });
    }

    private static <T> T require(T value) {
        if (value == null) {
            throw FulfillException.validation("target");
        }
        return value;
    }

    private static OffsetDateTime time(String value, String field) {
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException e) {
            throw FulfillException.validation(field);
        }
    }

    private static Map<String, Object> view(Visit v, LockedOrder locked) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("visitId", v.getId());
        out.put("status", v.getStatus().name());
        out.put("startedAt", v.getStartedAt());
        out.put("completedAt", v.getCompletedAt());
        out.put("completionSource", v.getCompletionSource());
        out.put("closedAt", v.getClosedAt());
        out.put("cancelledAt", v.getCancelledAt());
        out.put("orderStatus", locked.status().name());
        out.values().removeIf(java.util.Objects::isNull);
        return out;
    }
}
