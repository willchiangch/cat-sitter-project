package com.petsitter.application.service.order;

import com.petsitter.application.exception.OrderException;
import com.petsitter.domain.model.order.Order;
import com.petsitter.domain.model.order.OrderStatus;
import com.petsitter.domain.repository.order.AdminTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * ADMIN 唯讀範圍（SD-ORDER §3.5，使用者 2026-11-22）：訂單 {@code DISPUTED}，或曾有任何 {@code admin_tasks}（不論狀態）。
 * 讀取端點 E10、E12、E13、E18、E19、E27 與 {@code getOrderContexts.adminReadable} 共用。寫入（E16 / E29）各自另檢查。
 */
@Component
@RequiredArgsConstructor
public class AdminOrderAccessPolicy {

    private final AdminTaskRepository adminTaskRepository;

    public boolean readable(Order order) {
        return order.getStatus() == OrderStatus.DISPUTED || adminTaskRepository.existsByOrderId(order.getId());
    }

    public void requireReadable(Order order) {
        if (!readable(order)) {
            throw OrderException.forbidden();
        }
    }

    /** 批次版（一次查詢）。 */
    public Set<UUID> readableIds(Collection<Order> orders) {
        Set<UUID> out = new HashSet<>();
        Set<UUID> ids = new HashSet<>();
        for (Order o : orders) {
            ids.add(o.getId());
            if (o.getStatus() == OrderStatus.DISPUTED) {
                out.add(o.getId());
            }
        }
        if (!ids.isEmpty()) {
            out.addAll(adminTaskRepository.findOrderIdsWithAnyTask(ids));
        }
        return out;
    }
}
