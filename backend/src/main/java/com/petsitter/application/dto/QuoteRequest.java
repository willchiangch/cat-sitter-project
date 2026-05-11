package com.petsitter.application.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuoteRequest {
    private Integer adjustmentAmount;
    private Integer expectedTotalAmount;
    private String adjustmentReason;
    private Integer version; // 樂觀鎖版本號
}
