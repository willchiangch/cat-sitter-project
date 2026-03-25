package com.catsitter.api.entity;

import com.catsitter.api.entity.common.AuditableEntity;
import com.catsitter.api.entity.enums.ServiceType;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "visit_services")
public class VisitService extends AuditableEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @NotNull
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "visit_id", nullable = false)
  private Visit visit;

  /** nullable：環境清潔等服務不一定針對特定寵物 */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "pet_id")
  private Pet pet;

  @NotNull
  @Enumerated(EnumType.STRING)
  @Column(name = "service_type", nullable = false, length = 50)
  private ServiceType serviceType;

  @Column(length = 255)
  private String description;

  @Column(name = "sort_order", nullable = false)
  private Integer sortOrder = 0;

  @Column(name = "is_completed", nullable = false)
  private Boolean isCompleted = false;

  @Column(name = "photo_url", length = 1024)
  private String photoUrl;

  @Column(name = "completed_at")
  private OffsetDateTime completedAt;

  public UUID getId() { return id; }
  public Visit getVisit() { return visit; }
  public void setVisit(Visit visit) { this.visit = visit; }
  public Pet getPet() { return pet; }
  public void setPet(Pet pet) { this.pet = pet; }
  public ServiceType getServiceType() { return serviceType; }
  public void setServiceType(ServiceType serviceType) { this.serviceType = serviceType; }
  public String getDescription() { return description; }
  public void setDescription(String description) { this.description = description; }
  public Integer getSortOrder() { return sortOrder; }
  public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
  public Boolean getIsCompleted() { return isCompleted; }
  public void setIsCompleted(Boolean isCompleted) { this.isCompleted = isCompleted; }
  public String getPhotoUrl() { return photoUrl; }
  public void setPhotoUrl(String photoUrl) { this.photoUrl = photoUrl; }
  public OffsetDateTime getCompletedAt() { return completedAt; }
  public void setCompletedAt(OffsetDateTime completedAt) { this.completedAt = completedAt; }
}
