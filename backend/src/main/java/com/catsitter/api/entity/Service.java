package com.catsitter.api.entity;

import com.catsitter.api.entity.common.AuditableEntity;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "services")
public class Service extends AuditableEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @NotNull
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "sitter_profile_id", nullable = false)
  private Profile sitterProfile;

  @NotNull
  @Column(nullable = false, length = 255)
  private String name;

  @NotNull
  @Positive
  @Column(name = "base_price", nullable = false, precision = 10, scale = 2)
  private BigDecimal basePrice;

  @NotNull
  @Positive
  @Column(name = "duration_minutes", nullable = false)
  private Integer durationMinutes;

  @NotEmpty
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "supported_pet_types", columnDefinition = "jsonb")
  private List<String> supportedPetTypes;

  @Column(name = "bookable_start_date")
  private LocalDate bookableStartDate;

  @Column(name = "bookable_end_date")
  private LocalDate bookableEndDate;

  @Column(name = "advance_booking_days")
  private Integer advanceBookingDays = 0;

  @Column(name = "sort_order", nullable = false)
  private Integer sortOrder = 0;

  @Column(name = "is_active", nullable = false)
  private Boolean isActive = true;

  public UUID getId() { return id; }
  public Profile getSitterProfile() { return sitterProfile; }
  public void setSitterProfile(Profile sitterProfile) { this.sitterProfile = sitterProfile; }
  public String getName() { return name; }
  public void setName(String name) { this.name = name; }
  public BigDecimal getBasePrice() { return basePrice; }
  public void setBasePrice(BigDecimal basePrice) { this.basePrice = basePrice; }
  public Integer getDurationMinutes() { return durationMinutes; }
  public void setDurationMinutes(Integer durationMinutes) { this.durationMinutes = durationMinutes; }
  public List<String> getSupportedPetTypes() { return supportedPetTypes; }
  public void setSupportedPetTypes(List<String> supportedPetTypes) { this.supportedPetTypes = supportedPetTypes; }
  public LocalDate getBookableStartDate() { return bookableStartDate; }
  public void setBookableStartDate(LocalDate bookableStartDate) { this.bookableStartDate = bookableStartDate; }
  public LocalDate getBookableEndDate() { return bookableEndDate; }
  public void setBookableEndDate(LocalDate bookableEndDate) { this.bookableEndDate = bookableEndDate; }
  public Integer getAdvanceBookingDays() { return advanceBookingDays; }
  public void setAdvanceBookingDays(Integer advanceBookingDays) { this.advanceBookingDays = advanceBookingDays; }
  public Integer getSortOrder() { return sortOrder; }
  public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
  public Boolean getIsActive() { return isActive; }
  public void setIsActive(Boolean isActive) { this.isActive = isActive; }
}
