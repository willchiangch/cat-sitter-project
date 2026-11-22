package com.petsitter.application.dto.order;

import java.math.BigDecimal;
import java.util.List;

/**
 * E1 request（SD-ORDER §6.3）。型別規則（§6.1）：UUID 宣告為 String、金額 / 整數宣告為 BigDecimal，
 * 由 {@code OrderRequestValidator} 在判斷順序第 6 步驗證；不掛 {@code @Valid}。
 */
public record CreateOrderRequest(String sitterId, List<Group> groups, List<Answer> answers, BigDecimal expectedAmount) {

    public record Group(String planId, List<String> dates, BigDecimal dailyCount, List<String> petIds) {
    }

    /** {@code value}：字串或字串陣列。 */
    public record Answer(String questionId, Object value) {
    }
}
