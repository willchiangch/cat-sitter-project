package com.petsitter.application.event;

import java.util.UUID;

/** orderId 用來組通知連結 /owner/orders/{orderId}（Backlog 第 3 批 D1） */
public record VisitNotificationEvent(UUID userId, UUID orderId, String message) {}
