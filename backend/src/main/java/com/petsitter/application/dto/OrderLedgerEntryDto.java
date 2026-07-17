package com.petsitter.application.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 保母帳務總覽單筆項目 (PRD-009 主流程 C)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderLedgerEntryDto {
    private UUID orderId;
    private String ownerName;
    private Integer totalAmount;
    private OffsetDateTime paidAt;
    private OffsetDateTime completedAt;
    private OffsetDateTime payoutAt;
}
