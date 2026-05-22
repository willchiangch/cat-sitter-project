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
public class CareNoteItemDto {
    private UUID id;
    private String sectionType;
    private String title;
    private String content;
    private Integer sortOrder;
}
