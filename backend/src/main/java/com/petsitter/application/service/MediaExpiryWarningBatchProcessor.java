package com.petsitter.application.service;

import com.petsitter.domain.event.MediaExpiryWarningEvent;
import com.petsitter.domain.model.Order;
import com.petsitter.domain.model.OrderSnapshot;
import com.petsitter.domain.repository.OrderRepository;
import com.petsitter.domain.repository.OrderSnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

@Component
@RequiredArgsConstructor
public class MediaExpiryWarningBatchProcessor {
    private final OrderRepository orderRepository;
    private final OrderSnapshotRepository orderSnapshotRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processWarning(Order order) {
        OrderSnapshot snapshot = orderSnapshotRepository.findByOrderId(order.getId())
                .orElseThrow(() -> new IllegalStateException("Snapshot missing for order: " + order.getId()));
        
        // 計算精確的到期時間
        OffsetDateTime expiryTime = order.getCompletedAt().plusDays(snapshot.getMediaRetentionDays());

        // 發布非同步通知事件
        eventPublisher.publishEvent(new MediaExpiryWarningEvent(order.getId(), order.getOwner().getId(), expiryTime));
        
        // 更新並存檔標記
        order.setMediaExpiryWarned(true);
        orderRepository.saveAndFlush(order);
    }
}
