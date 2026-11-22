package com.petsitter.application.service.order;

import com.petsitter.application.exception.OrderException;
import com.petsitter.domain.model.order.OrderSnapshot;
import com.petsitter.domain.repository.order.OrderRepository;
import com.petsitter.domain.repository.order.OrderSnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** 媒體額度快照的寫入入口（SD-ORDER §2.3.5，給 SD-CARE）：只延長不縮短（-1 = 永久）。 */
@Service
@RequiredArgsConstructor
public class OrderSnapshotService {

    private final OrderSnapshotRepository orderSnapshotRepository;
    private final OrderRepository orderRepository;

    @Transactional
    public void extendMediaRetention(UUID orderId, int newRetentionDays) {
        OrderSnapshot s = orderSnapshotRepository.findByOrderId(orderId).orElseThrow(OrderException::notFound);
        if (longer(newRetentionDays, s.getMediaRetentionDays())) {
            s.setMediaRetentionDays(newRetentionDays);
        }
    }

    @Transactional
    public int extendMediaRetentionForSitter(UUID sitterId, int newRetentionDays) {
        int changed = 0;
        for (OrderSnapshot s : orderSnapshotRepository.findExtendableForSitter(sitterId)) {
            if (longer(newRetentionDays, s.getMediaRetentionDays())) {
                s.setMediaRetentionDays(newRetentionDays);
                changed++;
            }
        }
        return changed;
    }

    /** 單欄 UPDATE，不鎖訂單、不改其他欄位。 */
    @Transactional
    public void markMediaExpiryWarned(UUID orderId) {
        orderRepository.markMediaExpiryWarned(orderId);
    }

    private static boolean longer(int candidate, int current) {
        if (current == -1) {
            return false;
        }
        return candidate == -1 || candidate > current;
    }
}
