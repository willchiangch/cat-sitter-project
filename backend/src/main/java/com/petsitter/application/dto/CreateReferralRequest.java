package com.petsitter.application.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateReferralRequest {
    private UUID orderId; // 可為 null：D 流程 (主動分享，非訂單關聯)
    private UUID ownerId; // orderId 為 null 時必填，用於指定要通知的飼主
    private List<UUID> recommendedSitterIds;
    private String message;
}
