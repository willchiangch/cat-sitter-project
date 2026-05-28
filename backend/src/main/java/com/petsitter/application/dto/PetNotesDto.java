package com.petsitter.application.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PetNotesDto {
    private String medicalPersonalityNotes;
    private String environmentalNotes;
    private Integer version; // 樂觀鎖版本控制
}
