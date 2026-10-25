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
    /**
     * 該行程的照護日誌是否已正式送出（fix-batch12 U5）。飼主端只在 true 時顯示「查看日誌」；
     * 已完工但未送出、或逾期被系統關閉的行程點進去只會是空頁
     */
    private boolean reportSubmitted;
}
