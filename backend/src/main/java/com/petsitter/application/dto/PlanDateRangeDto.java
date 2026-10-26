package com.petsitter.application.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.UUID;

/**
 * 訂單所用方案的可預訂日期區間 (PRD-003 B)，供訂單變更精靈做前端防呆 (SD-016 §2.1)。
 * startDate / endDate 皆為 null = 常態開放；只有一端為 null 代表該端不限
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanDateRangeDto {
    private UUID planId;
    private String planName;
    /** 全域 non-null 序列化會吃掉 null，這裡強制輸出：null 有語意（該端不限），前端不必猜欄位缺漏 */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    private LocalDate startDate;
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    private LocalDate endDate;
}
