package com.petsitter.application.service;

import com.petsitter.application.service.order.OrderQueryService;
import com.petsitter.application.service.order.OrderSnapshotService;
import com.petsitter.domain.event.MediaExpiryWarningEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * PRD-013 媒體到期提醒單筆處理（SD-CARE 範圍，未重建）。SD-ORDER M15 / M32：不再整列 save 訂單，
 * 改呼叫 {@code OrderSnapshotService.markMediaExpiryWarned}（單欄 UPDATE）。
 */
@Component
@RequiredArgsConstructor
public class MediaExpiryWarningBatchProcessor {

    private final OrderSnapshotService orderSnapshotService;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public void processWarning(OrderQueryService.MediaExpiryView order) {
        OffsetDateTime expiryTime = order.completedAt().plusDays(order.mediaRetentionDays());
        eventPublisher.publishEvent(new MediaExpiryWarningEvent(order.orderId(), order.ownerId(), expiryTime));
        orderSnapshotService.markMediaExpiryWarned(order.orderId());
    }
}
