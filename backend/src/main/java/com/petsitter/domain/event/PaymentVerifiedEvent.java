package com.petsitter.domain.event;

import java.util.UUID;

/**
 * 保母核對入帳完成。
 * @param supplement true = 訂單變更的補款核帳（PRD-016 C.3），訂單早已成立，通知不可再說「交易正式成立」
 */
public record PaymentVerifiedEvent(UUID orderId, UUID sitterId, boolean supplement) {
}
