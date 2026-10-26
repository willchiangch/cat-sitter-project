package com.petsitter.domain.event;

import java.util.UUID;

/**
 * 保母婉拒待評估訂單（PRD-006 主流程 D / fix-batch13 K1），交易提交後通知飼主。
 * rejectReason 為保母填寫的原因（選填，未填為空字串）
 */
public record OrderRejectedBySitterEvent(UUID orderId, UUID sitterId, String rejectReason) {
}
