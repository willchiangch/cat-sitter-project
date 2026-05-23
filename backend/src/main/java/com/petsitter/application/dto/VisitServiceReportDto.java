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
    private boolean isEditable;
    private Integer version;
}
