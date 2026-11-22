package com.petsitter.application.service.order;

import com.petsitter.application.common.idempotency.IdempotencyContext;
import com.petsitter.application.common.idempotency.IdempotencyService;
import com.petsitter.domain.model.order.OrderLog;
import com.petsitter.domain.repository.order.OrderLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 同交易稽核（SD-ORDER §3.6.3）：傳播 {@code MANDATORY}，與狀態變更同交易；不使用 {@code REQUIRES_NEW}。
 * 一個操作一列；附帶轉移列在 payload {@code transitions[]}；有 {@code Idempotency-Key} 時自動記 {@code idempotencyKey}。
 * 也提供給 SD-FULFILL 寫行程類 action（§2.3.1）。
 */
@Service
@RequiredArgsConstructor
public class OrderLogWriter {

    private final OrderLogRepository orderLogRepository;
    private final OrderLockService orderLockService;
    private final IdempotencyService idempotencyService;
    private final Clock clock;

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID write(LockedOrder order, String actionType, Actor actor, Map<String, Object> payload) {
        orderLockService.requireHeld(order);
        Map<String, Object> body = new LinkedHashMap<>();
        if (payload != null) {
            body.putAll(payload);
        }
        body.putIfAbsent("transitions", List.of());
        idempotencyService.currentContext().map(IdempotencyContext::key)
                .ifPresent(k -> body.putIfAbsent("idempotencyKey", k));
        OrderLog log = OrderLog.builder()
                .orderId(order.orderId())
                .actionType(actionType)
                .operatorRole(actor.role())
                .operatorUserId(actor.userId())
                .payload(body)
                .createdAt(OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC))
                .build();
        return orderLogRepository.insert(log).getId();
    }

    /** {@code transitions[]} 的一個元素（{@code entity}、{@code id}、{@code from}、{@code to}）。 */
    public static Map<String, Object> transition(String entity, UUID id, Object from, Object to) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("entity", entity);
        t.put("id", String.valueOf(id));
        t.put("from", from == null ? null : String.valueOf(from));
        t.put("to", String.valueOf(to));
        return t;
    }
}
