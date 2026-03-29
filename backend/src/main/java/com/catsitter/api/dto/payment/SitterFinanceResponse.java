package com.catsitter.api.dto.payment;

import com.catsitter.api.entity.enums.OrderStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record SitterFinanceResponse(
    BigDecimal totalRevenue,          // 歷史總收益
    BigDecimal withdrawableBalance,   // 可提領餘額 (已完成且付款)
    BigDecimal monthlyRevenue,        // 本月累計營收
    int activeOrderCount,             // 進行中的訂單數 (CONFIRMED)
    BigDecimal averageOrderValue,     // 平均單價
    List<TransactionItem> recentTransactions
) {
    public record TransactionItem(
        UUID orderId,
        LocalDate date,
        String clientName,
        String catNames,
        BigDecimal amount,
        OrderStatus status,
        String pricingNotes
    ) {}
}
