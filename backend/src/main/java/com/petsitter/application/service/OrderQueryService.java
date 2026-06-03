package com.petsitter.application.service;

import com.petsitter.application.dto.OrderDetailResponseDto;

import java.util.UUID;

public interface OrderQueryService {
    /**
     * 查詢訂單詳情，執行 BOLA 驗證，並依訂單狀態過濾保母銀行帳戶資訊 (Sitter & Owner)
     */
    OrderDetailResponseDto getOrderDetail(UUID orderId, UUID requesterId);
}
