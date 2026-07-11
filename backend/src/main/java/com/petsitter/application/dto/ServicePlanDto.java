package com.petsitter.application.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServicePlanDto {
    private UUID id;
    private UUID sitterId;

    @NotBlank(message = "方案名稱不可為空")
    private String name;

    @NotNull(message = "價格不可為空")
    @Min(value = 1, message = "價格必須大於 0")
    private BigDecimal price;

    @NotNull(message = "每日最大接單量不可為空")
    @Min(value = 1, message = "每日最大接單量不可小於 1")
    private Integer dailyCapacity;

    @NotNull(message = "服務時長不可為空")
    @Min(value = 1, message = "服務時長必須大於 0 分鐘")
    @Builder.Default
    private Integer durationMinutes = 60;

    @Builder.Default
    private List<String> defaultTasks = List.of();

    @Builder.Default
    private List<String> applicablePetTypes = List.of();

    private String description;
    private LocalDate startDate;
    private LocalDate endDate;
    @Builder.Default
    private Boolean isRestricted = false;

    @Builder.Default
    private Integer sortOrder = 0;

    @Builder.Default
    private Boolean isActive = true;

    private Integer version;
}
