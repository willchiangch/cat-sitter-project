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
}
