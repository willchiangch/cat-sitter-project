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
public class OrderSummaryDto {
    private UUID id;
    private UUID ownerId;
    private String ownerName;
    private UUID sitterId;
    private String sitterName;
    private String status;
    private Integer totalAmount;
    /** 本次應付金額，定義同 OrderDetailResponseDto.amountDue（保母核對入帳時比對用） */
    private Integer amountDue;
    private String paymentProofUrl;
    private String paymentProofLastFive;
    private String scheduledDatesLabel;
}
