package com.petsitter.domain.event;

import java.util.UUID;

/** 飼主於付款前撤單（PRD-006 例外處理 / fix-batch10 H5），交易提交後通知保母 */
public record OrderCancelledByOwnerEvent(UUID orderId, UUID ownerId, String previousStatus) {
}
