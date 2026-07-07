package com.petsitter.application.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VisitServiceReportDto {
    private UUID reportId;
    private UUID visitId;
    private String status;
    private String content;
    private OffsetDateTime submittedAt;
    private List<ReportMediaDto> media;
    @com.fasterxml.jackson.annotation.JsonProperty("isEditable")
    private boolean isEditable;
    private Integer version;
    private String visitStatus;

    // --- 媒體保留過期屬性 (SD-013) ---
    private Integer mediaRetentionDays;
    private OffsetDateTime completedAt;
    private OffsetDateTime expiryTime;
    @com.fasterxml.jackson.annotation.JsonProperty("isPurged")
    private boolean isPurged;
}

