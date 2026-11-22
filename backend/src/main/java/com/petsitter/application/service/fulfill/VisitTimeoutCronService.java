package com.petsitter.application.service.fulfill;

import com.petsitter.application.service.order.Actor;
import com.petsitter.application.service.order.LockedOrder;
import com.petsitter.application.service.order.OrderClock;
import com.petsitter.application.service.order.OrderCronService;
import com.petsitter.application.service.order.OrderLifecycleService;
import com.petsitter.application.service.order.OrderLockService;
import com.petsitter.application.service.order.OrderLogWriter;
import com.petsitter.application.service.order.OrderQueryService;
import com.petsitter.domain.model.fulfill.Visit;
import com.petsitter.domain.model.order.VisitStatus;
import com.petsitter.domain.repository.fulfill.VisitRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * FC1 {@code IN_PROGRESS} 逾時出口（SD-FULFILL §4.6、T4）：唯讀候選（LIMIT 200）→ 每張訂單獨立交易、{@code tryLockForCron}
 * （SKIP LOCKED）→ 鎖後重驗 → DONE（SYSTEM）+ 基準重算 + 每行程一列 {@code VISIT_AUTO_COMPLETED} + 保母通知。
 * 時間預算 50 秒；單筆失敗回滾並計 failed；重驗後無行程符合計 skipped。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VisitTimeoutCronService {

    static final String JOB = "visits/in-progress-timeout";

    private final OrderCronService orderCronService;
    private final OrderLockService lockService;
    private final OrderLifecycleService lifecycleService;
    private final OrderLogWriter orderLogWriter;
    private final OrderQueryService orderQueryService;
    private final VisitRepository visitRepository;
    private final VisitNameResolver nameResolver;
    private final FulfillNotifier notifier;
    private final VisitRules rules;
    private final FulfillSettings settings;
    private final FulfillTx tx;
    private final OrderClock clock;

    public OrderCronService.CronResult run(UUID orderId) {
        CronScope scope = orderCronService.scope(orderId);
        OffsetDateTime started = clock.now();
        long deadline = System.nanoTime() + settings.getCronTimeBudget().toNanos();
        List<UUID> candidates = visitRepository.findInProgressTimeoutOrderIds(started.minus(settings.inProgressTimeout()),
                scope.orderId(), settings.getCronBatchSize());
        int processed = 0;
        int skipped = 0;
        int failed = 0;
        for (UUID id : new LinkedHashSet<>(candidates)) {
            if (System.nanoTime() > deadline) {
                break;
            }
            try {
                if (Boolean.TRUE.equals(tx.write(() -> processOrder(id)))) {
                    processed++;
                } else {
                    skipped++;
                }
            } catch (RuntimeException e) {
                log.error("cron {} failed for order {}", JOB, id, e);
                failed++;
            }
        }
        return new OrderCronService.CronResult(JOB, processed, skipped, failed, started, clock.now());
    }

    /** 單筆（呼叫端交易內）：鎖不到或重驗後無行程符合 → false（不寫任何資料）。 */
    boolean processOrder(UUID orderId) {
        Optional<LockedOrder> lo = lockService.tryLockForCron(orderId);
        if (lo.isEmpty()) {
            return false;
        }
        LockedOrder locked = lo.get();
        if (!VisitRules.RUNNING_ORDER.contains(locked.status())) {
            return false;
        }
        OffsetDateTime now = clock.now();
        List<Visit> due = visitRepository.findByOrderIdAndStatusAndStartedAtLessThanEqualOrderByServiceDateAscSequenceInDayAsc(
                orderId, VisitStatus.IN_PROGRESS, now.minus(settings.inProgressTimeout()));
        if (due.isEmpty()) {
            return false;
        }
        for (Visit v : due) {
            v.setStatus(VisitStatus.DONE);
            v.setCompletedAt(now);
            v.setCompletionSource(Visit.SOURCE_SYSTEM);
        }
        visitRepository.flush();
        lifecycleService.onVisitFinished(locked);
        OrderQueryService.OrderContextView ctx = orderQueryService.getOrderContexts(Set.of(orderId)).get(orderId);
        Map<UUID, String> titles = ctx == null ? Map.of() : ctx.planTitles();
        for (Visit v : due) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("visitId", v.getId().toString());
            payload.put("startedAt", com.petsitter.application.exception.FulfillException.ts(v.getStartedAt()));
            payload.put("completedAt", com.petsitter.application.exception.FulfillException.ts(now));
            payload.put("transitions", List.of(OrderLogWriter.transition("VISIT", v.getId(), VisitStatus.IN_PROGRESS, VisitStatus.DONE)));
            orderLogWriter.write(locked, "VISIT_AUTO_COMPLETED", Actor.SYSTEM, payload);
            notifier.autoCompleted(locked, new FulfillNotifier.NamedVisit(v.getId(), v.getServiceDate().toString(),
                    nameResolver.name(v, titles)), rules.careLogDeadline(v));
        }
        return true;
    }
}
