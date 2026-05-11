package com.petsitter.application.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookingRequest {
    private UUID ownerId;
    private UUID sitterId;
    private UUID planId; // 新增：服務方案 ID
    private List<LocalDate> dates;
    private String idempotencyKey;
}
