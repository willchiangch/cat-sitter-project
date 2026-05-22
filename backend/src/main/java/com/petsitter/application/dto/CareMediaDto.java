package com.petsitter.application.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CareMediaDto {
    private UUID id;
    private String caption;
    private String mediaUrl;
    private String mediaType;
    private OffsetDateTime createdAt;
}
