package com.petsitter.application.service.fulfill;

import com.petsitter.application.common.idempotency.IdempotencyClaim;
import com.petsitter.application.common.idempotency.IdempotencyContext;
import com.petsitter.application.common.idempotency.IdempotencyService;
import com.petsitter.application.dto.fulfill.FulfillRequests;
import com.petsitter.application.dto.fulfill.VisitResponses;
import com.petsitter.application.exception.FulfillErrorCode;
import com.petsitter.application.exception.FulfillException;
import com.petsitter.application.service.order.Actor;
import com.petsitter.application.service.order.CurrentActor;
import com.petsitter.application.service.order.OrderClock;
import com.petsitter.application.service.order.OrderLifecycleService;
import com.petsitter.application.service.order.OrderLogWriter;
import com.petsitter.application.service.order.OrderQueryService;
import com.petsitter.domain.model.fulfill.Visit;
import com.petsitter.domain.model.order.OrderStatus;
import com.petsitter.domain.model.order.VisitStatus;
import com.petsitter.domain.repository.fulfill.VisitRef;
import com.petsitter.domain.repository.fulfill.VisitRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 行程狀態機使用者端點 F3（T1 + O9）、F4（T2）、F5 命名（SD-FULFILL §3.1、§4.1、§4.2）。
 * 判斷順序：G4 → G5 → G6 → G7 → L（訂單鎖）→ 訂單狀態 → 行程狀態 → 期限 / 日期；一個操作一列 {@code order_logs}。
 */
@Service
@RequiredArgsConstructor
public class VisitLifecycleService {

    private final FulfillGuard guard;
    private final FulfillTx tx;
    private final FulfillRequestValidator validator;
    private final IdempotencyService idempotencyService;
    private final VisitRepository visitRepository;
    private final OrderLifecycleService lifecycleService;
    private final OrderLogWriter orderLogWriter;
    private final OrderQueryService orderQueryService;
    private final VisitNameResolver nameResolver;
    private final VisitRules rules;
    private final FulfillSettings settings;
    private final OrderClock clock;

    // ------------------------------------------------------------------ F3

    public VisitResponses.Started start(UUID orderId, UUID visitId) {
        CurrentActor actor = CurrentActor.fromToken();
        VisitRef ref = guard.visit(orderId, visitId);
        guard.requireSitter(ref.sitterId(), actor);
        IdempotencyContext ctx = validator.requireIdempotencyKey();
        IdempotencyClaim claim = idempotencyService.claim(ctx);
        return tx.inLockedTx(orderId, locked -> {
            OffsetDateTime now = clock.now();
            OrderStatus orderStatus = locked.status();
            if (!VisitRules.ACTIVE_ORDER.contains(orderStatus)) {
                throw FulfillException.orderState(orderStatus);
            }
            Visit v = load(visitId);
            requireVisit(v, VisitStatus.PENDING);
            OffsetDateTime zombieAt = rules.zombieAt(v.getServiceDate());
            if (!now.isBefore(zombieAt)) {
                throw FulfillException.deadline(VisitRules.DEADLINE_ZOMBIE, zombieAt);
            }
            if (VisitRules.taipeiDate(now).isBefore(v.getServiceDate())) {
                throw new FulfillException(FulfillErrorCode.VISIT_DATE_NOT_REACHED, Map.of("serviceDate", v.getServiceDate().toString()));
            }
            v.setStatus(VisitStatus.IN_PROGRESS);
            v.setStartedAt(now);
            visitRepository.flush();
            Actor sitter = Actor.sitter(actor.userId());
            OrderStatus after = lifecycleService.onVisitLeftPending(locked, visitId, VisitExitCause.STARTED, sitter);
            if (orderStatus != OrderStatus.CONFIRMED) {
                orderLogWriter.write(locked, "VISIT_STARTED", sitter, Map.of("visitId", visitId.toString(),
                        "transitions", java.util.List.of(OrderLogWriter.transition("VISIT", visitId, VisitStatus.PENDING, VisitStatus.IN_PROGRESS))));
            }
            VisitResponses.Started resp = new VisitResponses.Started(visitId, VisitStatus.IN_PROGRESS.name(), now, after.name());
            idempotencyService.complete(claim, 200, resp);
            return resp;
        });
    }

    // ------------------------------------------------------------------ F4

    public VisitResponses.Completed complete(UUID orderId, UUID visitId) {
        CurrentActor actor = CurrentActor.fromToken();
        VisitRef ref = guard.visit(orderId, visitId);
        guard.requireSitter(ref.sitterId(), actor);
        IdempotencyContext ctx = validator.requireIdempotencyKey();
        IdempotencyClaim claim = idempotencyService.claim(ctx);
        return tx.inLockedTx(orderId, locked -> {
            OffsetDateTime now = clock.now();
            if (!VisitRules.RUNNING_ORDER.contains(locked.status())) {
                throw FulfillException.orderState(locked.status());
            }
            Visit v = load(visitId);
            requireVisit(v, VisitStatus.IN_PROGRESS);
            v.setStatus(VisitStatus.DONE);
            v.setCompletedAt(now);
            v.setCompletionSource(Visit.SOURCE_SITTER);
            visitRepository.flush();
            lifecycleService.onVisitFinished(locked);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("visitId", visitId.toString());
            payload.put("completedAt", FulfillException.ts(now));
            payload.put("transitions", java.util.List.of(OrderLogWriter.transition("VISIT", visitId, VisitStatus.IN_PROGRESS, VisitStatus.DONE)));
            orderLogWriter.write(locked, "VISIT_COMPLETED", Actor.sitter(actor.userId()), payload);
            VisitResponses.Completed resp = new VisitResponses.Completed(visitId, VisitStatus.DONE.name(), now,
                    Visit.SOURCE_SITTER, rules.careLogDeadline(v));
            idempotencyService.complete(claim, 200, resp);
            return resp;
        });
    }

    // ------------------------------------------------------------------ F5

    public VisitResponses.Renamed rename(UUID orderId, UUID visitId, FulfillRequests.Rename req, OffsetDateTime receivedAt) {
        CurrentActor actor = CurrentActor.fromToken();
        VisitRef ref = guard.visit(orderId, visitId);
        guard.requireSitter(ref.sitterId(), actor);
        String name = validator.strippedString(req == null ? null : req.name(), "name", 0, settings.getVisitNameMax());
        String newName = name.isEmpty() ? null : name;
        return tx.inLockedTx(orderId, locked -> {
            if (!VisitRules.ACTIVE_ORDER.contains(locked.status())) {
                throw FulfillException.orderState(locked.status());
            }
            Visit v = load(visitId);
            if (v.getStatus() == VisitStatus.CLOSED_BY_SYSTEM || v.getStatus() == VisitStatus.CANCELLED) {
                throw FulfillException.visitState(v.getStatus(), v.getCompletionSource());
            }
            if (rules.careLogExpired(v, receivedAt)) {
                throw FulfillException.deadline(VisitRules.DEADLINE_CARE_LOG, rules.careLogDeadline(v));
            }
            String oldName = v.getCustomName();
            v.setCustomName(newName);
            visitRepository.flush();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("visitId", visitId.toString());
            payload.put("oldName", oldName);
            payload.put("newName", newName);
            orderLogWriter.write(locked, "VISIT_RENAMED", Actor.sitter(actor.userId()), payload);
            OrderQueryService.OrderContextView octx = orderQueryService.getOrderContexts(Set.of(orderId)).get(orderId);
            return new VisitResponses.Renamed(visitId, nameResolver.name(v, octx == null ? Map.of() : octx.planTitles()), newName);
        });
    }

    // ------------------------------------------------------------------ 共用

    /** 鎖後首次載入（SD-FULFILL §2.5 第 3 點）。 */
    private Visit load(UUID visitId) {
        return visitRepository.findById(visitId).orElseThrow(FulfillException::notFound);
    }

    private static void requireVisit(Visit v, VisitStatus expected) {
        if (v.getStatus() != expected) {
            throw FulfillException.visitState(v.getStatus(), v.getCompletionSource());
        }
    }
}
