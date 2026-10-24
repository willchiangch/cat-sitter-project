package com.petsitter.application.service;

import com.petsitter.application.exception.CapacityFullException;
import com.petsitter.domain.model.ServicePlan;
import com.petsitter.domain.model.Visit;
import com.petsitter.domain.repository.VisitRepository;
import com.petsitter.infrastructure.lock.AdvisoryLockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 保母接單時的每日容量保護（SD-005 §1.2 / SD-006 §2.1）。
 *
 * <p>所有會把訂單從 PENDING 轉成佔用容量狀態（PENDING_PAYMENT）的入口都必須在同一筆交易內、
 * 改狀態之前呼叫 {@link #lockAndCheck}：原價快速接單 {@link ConfirmOrderService#confirmOrder}
 * 與議價報價 {@link EvaluationService#sendQuote}。2026-10 backlog G1 之前報價路徑沒有呼叫，
 * 保母可以先原價接滿某天、再用報價接同一天的單造成超賣，兩條路徑併發時也互相看不到。
 *
 * <p>容量口徑維持既有實作（待產品決策，見 SD-006 §5）：
 * <ul>
 *   <li>count 只算其他已佔用訂單（CONFIRMED/PENDING_PAYMENT/IN_PROGRESS/COMPLETED）的行程，不含本單自己</li>
 *   <li>多方案訂單以下單時的第一個方案（order.planId）的 dailyCapacity 為準</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CapacityGuard {

    private final AdvisoryLockService advisoryLockService;
    private final VisitRepository visitRepository;

    /**
     * 依保母 + 日期取排序後的 pg_advisory_xact_lock，鎖內重新 count 已佔用行程，任一天 {@code >= dailyCapacity}
     * 即拋 {@link CapacityFullException}。鎖跟著交易結束釋放，所以呼叫方必須在同一筆交易內改訂單狀態。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockAndCheck(UUID sitterId, UUID orderId, ServicePlan plan) {
        List<Visit> visits = visitRepository.findByOrderId(orderId);

        List<Long> lockKeys = visits.stream()
                .map(v -> v.getScheduledAt().toLocalDate())
                .distinct()
                .map((LocalDate date) -> AdvisoryLockService.generateLockKey(sitterId, date))
                .sorted()
                .toList();
        advisoryLockService.acquireLocks(lockKeys);

        for (Visit visit : visits) {
            OffsetDateTime scheduledAt = visit.getScheduledAt();
            long bookedCount = visitRepository.countBookedVisitsBySitterIdAndDate(sitterId, scheduledAt);

            log.info("[CapacityGuard] Order {}, Date: {}, Booked: {}, Capacity: {}", orderId,
                    scheduledAt.toLocalDate(), bookedCount, plan.getDailyCapacity());

            if (bookedCount >= plan.getDailyCapacity()) {
                throw new CapacityFullException(
                        "該日期預約已滿 (方案: " + plan.getName() + "): " + scheduledAt.toLocalDate());
            }
        }
    }
}
