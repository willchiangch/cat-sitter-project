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
public class CareNoteTemplateDto {
    private UUID id;
    private String name;
    private List<CareNoteItemDto> items;
    private OffsetDateTime updatedAt;
}
