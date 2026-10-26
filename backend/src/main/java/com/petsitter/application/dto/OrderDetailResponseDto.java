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
    private String ownerName;
    private UUID sitterId;
    private String status;
    private Integer totalAmount;
    /**
     * 本次應付金額 (SD-007 §4.6 / SD-016 §4.1)：首次付款 = totalAmount；
     * 訂單變更補款 (最新一筆 ModificationRequest 為 M_DONE 且 diffAmount > 0) = 差額，不是新總額
     */
    private Integer amountDue;
    private Integer version;
    private Integer adjustmentAmount;
    private String adjustmentReason;
    /**
     * 是否有可檢視的付款憑證 (SD-007 §4.7)。2026-10 起不再回傳憑證網址（過去是 GCS 公開網址），
     * 前端需另外呼叫 GET /api/orders/{orderId}/payment-proof/signed-url 取短效簽名網址
     */
    private boolean hasPaymentProof;
    private String paymentProofLastFive;
    private boolean disclaimerAgreed;
    private OffsetDateTime disclaimerAgreedAt;
    private OffsetDateTime paidAt;
    private OffsetDateTime completedAt;
    private OffsetDateTime payoutAt;
    private List<OrderItem> items; // 預約內容快照清單
    /**
     * 取消者：OWNER = 飼主付款前撤單（fix-batch10 H5）；SITTER = 保母婉拒（fix-batch13 K1，
     * 2026-10-26 前婉拒的舊訂單欄位為 NULL，依 ORDER_REJECTED 日誌補判為 SITTER）；訂單變更取消為 null
     */
    private String cancelledBy;
    /** 保母婉拒原因（cancelledBy = SITTER 時才有值，保母未填為空字串），飼主詳情據此顯示（fix-batch13 K1） */
    private String sitterRejectReason;
    /** 目前是否可由飼主撤單（OwnerCancellationService.rejectReason 為空），前端據此顯示「取消預約」 */
    private boolean cancellableByOwner;
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    private BankAccountInfo sitterPaymentInfo; // 解密後之保母收款資訊 (動態過濾，其餘狀態為 null)
}
