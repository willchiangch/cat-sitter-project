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
    private String paymentProofUrl;
    private String paymentProofLastFive;
    private String scheduledDatesLabel;
}
