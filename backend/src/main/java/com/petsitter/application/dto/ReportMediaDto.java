package com.petsitter.application.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportMediaDto {
    private UUID mediaId;
    private String mediaUrl;
    private String mediaType;
    private String caption;
    private Integer version;
}
