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
    /**
     * 是否有可檢視的付款憑證 (SD-007 §4.7)。2026-10 起不再回傳憑證網址（過去是 GCS 公開網址），
     * 前端需另外呼叫 GET /api/orders/{orderId}/payment-proof/signed-url 取短效簽名網址
     */
    private boolean hasPaymentProof;
    private String paymentProofLastFive;
    private String scheduledDatesLabel;
}
