package com.petsitter.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderItem {
    private String category;
    private String serviceName;
    private Integer unitPrice;
    private Integer quantity;
    // 新增排程相關欄位
    private java.util.UUID planId;
    private java.util.List<String> dates;
    private Integer timesPerDay;
    // 新增毛孩關聯欄位（向後相容：舊訂單 JSON 沒有此欄位時，預設為空 List 避免 NPE）
    private java.util.List<java.util.UUID> petIds = new java.util.ArrayList<>();
}
