package com.petsitter.application.service.order;

import com.petsitter.application.service.NotificationService;
import com.petsitter.application.service.notify.NotificationCommand;
import com.petsitter.domain.model.order.Order;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * 轉移交易內的通知 enqueue（SD-ORDER §3.6.4、§7.2）；交易要求由 {@code NotificationService.enqueue}（MANDATORY）把關。連結：OWNER {@code /owner/orders/{id}}、
 * SITTER {@code /sitter/orders/{id}}；變更類 {@code /orders/{id}/modifications/{modificationId}}。
 */
@Component
@RequiredArgsConstructor
public class OrderNotifier {

    private final NotificationService notificationService;

    public void owner(Order order, String type, boolean mandatory, Map<String, Object> params) {
        send(order, order.getOwnerId(), "OWNER", type, mandatory, "/owner/orders/" + order.getId(), params);
    }

    public void sitter(Order order, String type, boolean mandatory, Map<String, Object> params) {
        send(order, order.getSitterId(), "SITTER", type, mandatory, "/sitter/orders/" + order.getId(), params);
    }

    public void both(Order order, String type, boolean mandatory, Map<String, Object> params) {
        owner(order, type, mandatory, params);
        sitter(order, type, mandatory, params);
    }

    public void modification(Order order, String recipientRole, UUID modificationId, String type,
                             Map<String, Object> params) {
        UUID recipient = "OWNER".equals(recipientRole) ? order.getOwnerId() : order.getSitterId();
        send(order, recipient, recipientRole, type, false,
                "/orders/" + order.getId() + "/modifications/" + modificationId, params);
    }

    private void send(Order order, UUID recipient, String role, String type, boolean mandatory, String link,
                      Map<String, Object> params) {
        Map<String, Object> p = new java.util.HashMap<>(params == null ? Map.of() : params);
        p.putIfAbsent("orderId", order.getId().toString());
        p.values().removeIf(java.util.Objects::isNull);
        notificationService.enqueue(new NotificationCommand(recipient, role, type, mandatory, order.getId(), link, p));
    }
}
