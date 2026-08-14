package com.petsitter.application.service;

import com.petsitter.application.dto.OrderDetailResponseDto;
import com.petsitter.application.dto.OrderSummaryDto;
import com.petsitter.application.dto.SitterLedgerResponse;
import com.petsitter.application.dto.VisitSummaryDto;

import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

public interface OrderQueryService {
    /**
     * 查詢訂單詳情，執行 BOLA 驗證，並依訂單狀態過濾保母銀行帳戶資訊 (Sitter & Owner)
     */
    OrderDetailResponseDto getOrderDetail(UUID orderId, UUID requesterId);

    List<OrderSummaryDto> getMyOrdersAsOwner(UUID ownerId);

    List<OrderSummaryDto> getMyOrdersAsSitter(UUID sitterId);

    /**
     * 保母帳務總覽 (PRD-009 主流程 C)：依結案日篩選月份，統計該月總收入
     */
    SitterLedgerResponse getSitterLedger(UUID sitterId, YearMonth month);

    /**
     * 查詢某訂單底下的行程清單（依日期排序），供前端串接打卡/日誌回報頁面。
     * 沿用 getOrderDetail 同一套 BOLA 驗證（僅訂單的飼主或保母可查）。
     */
    List<VisitSummaryDto> getOrderVisits(UUID orderId, UUID requesterId);
}
