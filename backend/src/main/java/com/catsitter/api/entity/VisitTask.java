package com.catsitter.api.entity;

import com.catsitter.api.entity.common.AuditableEntity;
import com.catsitter.api.entity.enums.TaskType;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "visit_tasks")
public class VisitTask extends AuditableEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @NotNull
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "visit_id", nullable = false)
  private Visit visit;

  /** nullable：環境清潔等任務不一定針對特定寵物 */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "pet_id")
  private Pet pet;

  @NotNull
  @Enumerated(EnumType.STRING)
  @Column(name = "task_type", nullable = false, length = 50)
  private TaskType taskType;

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
  public TaskType getTaskType() { return taskType; }
  public void setTaskType(TaskType taskType) { this.taskType = taskType; }
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
