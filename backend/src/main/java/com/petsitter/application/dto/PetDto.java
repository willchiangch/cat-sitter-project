package com.petsitter.application.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PetDto {
    private UUID id;
    private UUID ownerId;
    private String name;
    private String species;
    private String gender;
    private Boolean neutered;
    private BigDecimal weight;
    private Integer birthYear;
    private String photoUrl;
    private String medicalPersonalityNotes;
    private String environmentalNotes;
    private Integer version;
}
