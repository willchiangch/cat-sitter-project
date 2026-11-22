package com.petsitter.application.service.fulfill;

import com.petsitter.application.service.order.OrderClock;
import com.petsitter.domain.model.fulfill.Visit;
import com.petsitter.domain.model.order.OrderStatus;
import com.petsitter.domain.model.order.VisitStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.Set;

/** 行程 / 檢核項 / 日誌寫入窗口的純規則（SD-FULFILL §3.1、§3.2、§3.4）；時間一律由呼叫端以注入 Clock 傳入。 */
@Component
@RequiredArgsConstructor
public class VisitRules {

    /** 開始、命名、檢核項編輯：訂單 CONFIRMED / IN_PROGRESS / DISPUTED。 */
    public static final Set<OrderStatus> ACTIVE_ORDER = EnumSet.of(OrderStatus.CONFIRMED, OrderStatus.IN_PROGRESS, OrderStatus.DISPUTED);
    /** 結束、打勾、日誌寫入：訂單 IN_PROGRESS / DISPUTED。 */
    public static final Set<OrderStatus> RUNNING_ORDER = EnumSet.of(OrderStatus.IN_PROGRESS, OrderStatus.DISPUTED);

    public static final String DEADLINE_ZOMBIE = "VISIT_ZOMBIE_AT";
    public static final String DEADLINE_CARE_LOG = "CARE_LOG_EDIT_ENDS_AT";

    private final FulfillSettings settings;

    /** {@code VISIT_ZOMBIE_AT} = service_date 00:00（Asia/Taipei）+ VISIT_ZOMBIE_HOURS。 */
    public OffsetDateTime zombieAt(LocalDate serviceDate) {
        return OrderClock.startOfDay(serviceDate).plus(settings.visitZombie());
    }

    /** {@code CARE_LOG_EDIT_ENDS_AT} = completed_at + CARE_LOG_EDIT_HOURS（DONE 行程）。 */
    public OffsetDateTime careLogDeadline(Visit v) {
        return v.getCompletedAt() == null ? null : v.getCompletedAt().plus(settings.careLogEdit());
    }

    /** 逾期為衍生值：DONE 且 {@code at ≥ completed_at + CARE_LOG_EDIT_HOURS}（PRD-022 BR-1）。 */
    public boolean careLogExpired(Visit v, OffsetDateTime at) {
        OffsetDateTime d = careLogDeadline(v);
        return v.getStatus() == VisitStatus.DONE && d != null && !at.isBefore(d);
    }

    public static LocalDate taipeiDate(OffsetDateTime t) {
        return t.atZoneSameInstant(OrderClock.TAIPEI).toLocalDate();
    }
}
