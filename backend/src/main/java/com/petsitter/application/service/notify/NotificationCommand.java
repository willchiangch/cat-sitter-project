package com.petsitter.application.service.notify;

import java.util.Map;
import java.util.UUID;

/**
 * {@code NotificationService.enqueue()} 的參數（SD-ORDER §2.4.2）：收件者、收件角色（OWNER / SITTER）、
 * 類型代碼（SD-ORDER §7.2）、是否不可關閉、關聯訂單、連結、範本參數。
 */
public record NotificationCommand(UUID recipientUserId, String recipientRole, String type, boolean mandatory,
                                  UUID relatedOrderId, String linkPath, Map<String, Object> params) {

    public NotificationCommand {
        if (recipientUserId == null || type == null) {
            throw new IllegalArgumentException("recipientUserId / type required");
        }
        if (!"OWNER".equals(recipientRole) && !"SITTER".equals(recipientRole)) {
            throw new IllegalArgumentException("recipientRole must be OWNER or SITTER");
        }
        params = params == null ? Map.of() : Map.copyOf(params);
    }
}
