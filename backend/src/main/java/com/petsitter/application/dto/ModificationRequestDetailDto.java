package com.petsitter.application.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * 進行中的變更請求詳情 (PRD-016)，供保母報價頁與飼主確認頁串接真實資料
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModificationRequestDetailDto {
    private UUID id;
    private UUID orderId;
    private String status;
    /**
     * 訂單目前狀態 (MODIFYING / PENDING_PAYMENT / REFUND_VERIFY ...)。前端依 status + orderStatus + diffAmount
     * 正負收斂成「目前唯一可執行的動作」，例如 M_DONE + REFUND_VERIFY 只剩退款憑證/確認收到退款 (SD-016 §2.2)
     */
    private String orderStatus;
    private String requestedBy;
    private Integer diffAmount;
    private Integer newTotalAmount;
    private Integer currentOrderTotalAmount;
    private Integer orderVersion;
    private List<String> dates;
    private String refundProofUrl;
}
