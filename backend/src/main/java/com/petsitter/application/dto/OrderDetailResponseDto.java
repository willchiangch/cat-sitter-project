package com.petsitter.application.dto;

import com.petsitter.domain.model.BankAccountInfo;
import com.petsitter.domain.model.OrderItem;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderDetailResponseDto {
    private UUID id;
    private UUID ownerId;
    private UUID sitterId;
    private String status;
    private Integer totalAmount;
    private Integer adjustmentAmount;
    private String adjustmentReason;
    private String paymentProofUrl;
    private String paymentProofLastFive;
    private boolean disclaimerAgreed;
    private OffsetDateTime disclaimerAgreedAt;
    private OffsetDateTime paidAt;
    private OffsetDateTime completedAt;
    private OffsetDateTime payoutAt;
    private List<OrderItem> items; // 預約內容快照清單
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    private BankAccountInfo sitterPaymentInfo; // 解密後之保母收款資訊 (動態過濾，其餘狀態為 null)
}
