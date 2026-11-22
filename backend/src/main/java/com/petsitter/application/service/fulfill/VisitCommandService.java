package com.petsitter.application.service.fulfill;

import com.petsitter.application.service.order.LockedOrder;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * SD-FULFILL 的行程寫入介面（SD-ORDER §2.4.2、SD-FULFILL §2.3.1）。寫入方法皆 {@code MANDATORY}，
 * 只能在已持有訂單鎖的交易內呼叫（呼叫者限 order 套件與 SD-FULFILL 自身，{@code OrderAggregateArchTest}）。
 */
public interface VisitCommandService {

    List<UUID> createVisits(LockedOrder order, List<VisitSpec> specs);

    List<UUID> cancelPendingVisits(LockedOrder order, OffsetDateTime now);

    /** 任一非 PENDING → {@code VisitNotPendingException}，整批不改。 */
    void cancelVisits(LockedOrder order, Set<UUID> visitIds, OffsetDateTime now);

    List<UUID> closeZombieVisits(LockedOrder order, OffsetDateTime now, Duration zombieAfter);
}
