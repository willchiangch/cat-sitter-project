package com.petsitter.application.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CareNoteDto {
    private UUID careNoteId;
    private UUID sitterId;
    private UUID ownerId;
    private Map<String, List<CareNoteItemDto>> sections;
}
