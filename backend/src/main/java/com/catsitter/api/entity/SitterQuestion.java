package com.catsitter.api.entity;

import com.catsitter.api.entity.common.AuditableEntity;
import com.catsitter.api.entity.enums.QuestionType;
import com.catsitter.api.entity.enums.TargetPetType;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "sitter_questions")
public class SitterQuestion extends AuditableEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @NotNull
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "sitter_profile_id", nullable = false)
  private Profile sitterProfile;

  @NotNull
  @Enumerated(EnumType.STRING)
  @Column(name = "target_pet_type", nullable = false, length = 50)
  private TargetPetType targetPetType;

  @NotBlank
  @Column(name = "question_text", nullable = false, length = 1024)
  private String questionText;

  @NotNull
  @Enumerated(EnumType.STRING)
  @Column(name = "question_type", nullable = false, length = 50)
  private QuestionType type = QuestionType.TEXT;

  @Column(name = "is_required", nullable = false)
  private Boolean required = true;

  @ElementCollection
  @CollectionTable(name = "sitter_question_options", joinColumns = @JoinColumn(name = "question_id"))
  @Column(name = "option_text")
  @OrderColumn(name = "option_order")
  private List<String> options = new ArrayList<>();

  @Column(name = "sort_order", nullable = false)
  private Integer sortOrder = 0;

  @Column(name = "is_active", nullable = false)
  private Boolean isActive = true;

  public UUID getId() { return id; }
  public Profile getSitterProfile() { return sitterProfile; }
  public void setSitterProfile(Profile sitterProfile) { this.sitterProfile = sitterProfile; }
  public TargetPetType getTargetPetType() { return targetPetType; }
  public void setTargetPetType(TargetPetType targetPetType) { this.targetPetType = targetPetType; }
  public String getQuestionText() { return questionText; }
  public void setQuestionText(String questionText) { this.questionText = questionText; }
  public QuestionType getType() { return type; }
  public void setType(QuestionType type) { this.type = type; }
  public Boolean getRequired() { return required; }
  public void setRequired(Boolean required) { this.required = required; }
  public List<String> getOptions() { return options; }
  public void setOptions(List<String> options) { this.options = options; }
  public Integer getSortOrder() { return sortOrder; }
  public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
  public Boolean getIsActive() { return isActive; }
  public void setIsActive(Boolean isActive) { this.isActive = isActive; }
}
