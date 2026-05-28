package com.petsitter.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "pets")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class Pet extends BaseEntity {

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 50)
    private String species; // e.g. CAT, DOG

    @Column(length = 20)
    private String gender;  // MALE, FEMALE

    private Boolean neutered;

    @Column(precision = 5, scale = 2)
    private BigDecimal weight;

    @Column(name = "birth_year")
    private Integer birthYear;

    @Column(name = "photo_url", length = 512)
    private String photoUrl;

    @Column(name = "medical_personality_notes", columnDefinition = "TEXT")
    private String medicalPersonalityNotes;

    @Column(name = "environmental_notes", columnDefinition = "TEXT")
    private String environmentalNotes;
}
