package com.catsitter.api.entity;

import com.catsitter.api.entity.common.AuditableEntity;
import com.catsitter.api.entity.enums.PetGender;
import com.catsitter.api.entity.enums.PetHealthStatus;
import com.catsitter.api.entity.enums.PetSpecies;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "pets")
@org.hibernate.annotations.SQLDelete(sql = "UPDATE pets SET deleted_at = NOW() WHERE id = ?")
@org.hibernate.annotations.SQLRestriction("deleted_at IS NULL")
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

  @NotNull
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 10)
  private PetGender gender;
  
  @NotNull
  @Enumerated(EnumType.STRING)
  @Column(name = "neutered_status", nullable = false, length = 20)
  private PetHealthStatus neuteredStatus;

  @NotNull
  @Enumerated(EnumType.STRING)
  @Column(name = "vaccination_status", nullable = false, length = 20)
  private PetHealthStatus vaccinationStatus;

  @NotNull
  @Enumerated(EnumType.STRING)
  @Column(name = "deworming_status", nullable = false, length = 20)
  private PetHealthStatus dewormingStatus;

  @PositiveOrZero
  @Column(name = "weight_kg", precision = 5, scale = 2)
  private BigDecimal weightKg;

  @Column(name = "birth_date")
  private LocalDate birthDate;

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
  public void setId(UUID id) { this.id = id; }
  public Profile getClientProfile() { return clientProfile; }
  public void setClientProfile(Profile clientProfile) { this.clientProfile = clientProfile; }
  public String getName() { return name; }
  public void setName(String name) { this.name = name; }
  public PetSpecies getSpecies() { return species; }
  public void setSpecies(PetSpecies species) { this.species = species; }
  public PetGender getGender() { return gender; }
  public void setGender(PetGender gender) { this.gender = gender; }
  public PetHealthStatus getNeuteredStatus() { return neuteredStatus; }
  public void setNeuteredStatus(PetHealthStatus neuteredStatus) { this.neuteredStatus = neuteredStatus; }
  public PetHealthStatus getVaccinationStatus() { return vaccinationStatus; }
  public void setVaccinationStatus(PetHealthStatus vaccinationStatus) { this.vaccinationStatus = vaccinationStatus; }
  public PetHealthStatus getDewormingStatus() { return dewormingStatus; }
  public void setDewormingStatus(PetHealthStatus dewormingStatus) { this.dewormingStatus = dewormingStatus; }
  public BigDecimal getWeightKg() { return weightKg; }
  public void setWeightKg(BigDecimal weightKg) { this.weightKg = weightKg; }
  public LocalDate getBirthDate() { return birthDate; }
  public void setBirthDate(LocalDate birthDate) { this.birthDate = birthDate; }
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
