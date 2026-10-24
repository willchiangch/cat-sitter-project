package com.petsitter.application.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 飼主撤單請求。version 為飼主畫面上看到的訂單版本：保母在這之間接單 / 報價（version 已變）時回 409，
 * 避免飼主以為撤的是「評估中」的單，實際撤掉的是保母剛報價的單。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OwnerCancelOrderRequest {
    @NotNull(message = "version 不可為空")
    private Integer version;
}
