package com.petsitter.application.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 供訂單詳情頁/訂單列表頁串接行程打卡/日誌回報頁面用的精簡行程摘要
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VisitSummaryDto {
    private UUID id;
    private String status; // PENDING, IN_PROGRESS, DONE, CLOSED_BY_SYSTEM
    private OffsetDateTime scheduledAt;
    private OffsetDateTime finishedAt;
}
