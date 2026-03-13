package com.catsitter.api.entity;

import com.catsitter.api.entity.common.AuditableEntity;
import com.catsitter.api.entity.enums.PetGender;
import com.catsitter.api.entity.enums.PetSpecies;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "pets")
public class Pet extends AuditableEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @NotNull
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "client_profile_id", nullable = false)
  private Profile clientProfile;

  @NotBlank
  @Column(nullable = false, length = 255)
  private String name;

  @NotNull
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 50)
  private PetSpecies species;

  @Enumerated(EnumType.STRING)
  @Column(length = 10)
  private PetGender gender;

  @Column(name = "is_neutered")
  private Boolean isNeutered;

  @PositiveOrZero
  @Column(name = "weight_kg", precision = 5, scale = 2)
  private BigDecimal weightKg;

  @Column(name = "avatar_url", length = 1024)
  private String avatarUrl;

  @Column(name = "medical_notes", columnDefinition = "TEXT")
  private String medicalNotes;

  @Column(name = "dietary_notes", columnDefinition = "TEXT")
  private String dietaryNotes;

  @Column(name = "personality_notes", columnDefinition = "TEXT")
  private String personalityNotes;

  @Column(name = "other_notes", columnDefinition = "TEXT")
  private String otherNotes;

  public UUID getId() { return id; }
  public Profile getClientProfile() { return clientProfile; }
  public void setClientProfile(Profile clientProfile) { this.clientProfile = clientProfile; }
  public String getName() { return name; }
  public void setName(String name) { this.name = name; }
  public PetSpecies getSpecies() { return species; }
  public void setSpecies(PetSpecies species) { this.species = species; }
  public PetGender getGender() { return gender; }
  public void setGender(PetGender gender) { this.gender = gender; }
  public Boolean getIsNeutered() { return isNeutered; }
  public void setIsNeutered(Boolean isNeutered) { this.isNeutered = isNeutered; }
  public BigDecimal getWeightKg() { return weightKg; }
  public void setWeightKg(BigDecimal weightKg) { this.weightKg = weightKg; }
  public String getAvatarUrl() { return avatarUrl; }
  public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }
  public String getMedicalNotes() { return medicalNotes; }
  public void setMedicalNotes(String medicalNotes) { this.medicalNotes = medicalNotes; }
  public String getDietaryNotes() { return dietaryNotes; }
  public void setDietaryNotes(String dietaryNotes) { this.dietaryNotes = dietaryNotes; }
  public String getPersonalityNotes() { return personalityNotes; }
  public void setPersonalityNotes(String personalityNotes) { this.personalityNotes = personalityNotes; }
  public String getOtherNotes() { return otherNotes; }
  public void setOtherNotes(String otherNotes) { this.otherNotes = otherNotes; }
}
