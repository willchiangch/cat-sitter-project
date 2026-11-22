package com.petsitter.application.dto.order;

import java.util.List;

/**
 * E3 / E4 訂單列表分頁回應（SD-ORDER §6.3 E3 / E4）。{@code serverMonth} = 注入 {@code Clock} 換算 Asia/Taipei 的當下月份
 * （{@code YYYY-MM}，語意同 E30），供歷史分頁月份篩選以伺服器月份為基準。
 */
public record OrderListResponse(List<OrderCard> content, int page, int size, long totalElements, String serverMonth) {
}
