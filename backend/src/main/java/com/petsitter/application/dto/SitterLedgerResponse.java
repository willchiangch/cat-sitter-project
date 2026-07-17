package com.petsitter.application.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 保母帳務總覽回應 (PRD-009 主流程 C：月份篩選 + 總收入)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SitterLedgerResponse {
    private String yearMonth;
    private Integer totalRevenue;
    private List<OrderLedgerEntryDto> entries;
}
