package com.petsitter.application.dto;

import com.petsitter.domain.model.OrderItem;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModificationPayloadDto {
    private List<OrderItem> items;
    private List<LocalDate> dates;
    private Integer totalDays;
    private String reason;
}