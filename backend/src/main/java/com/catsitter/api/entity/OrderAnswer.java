package com.catsitter.api.entity;

import com.catsitter.api.entity.common.AuditableEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

@Entity
@Table(name = "order_answers")
public class OrderAnswer extends AuditableEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @NotNull
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "order_id", nullable = false)
  private Order order;

  @NotNull
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "question_id", nullable = false)
  private SitterQuestion question;

  @NotBlank
  @Column(name = "answer_text", nullable = false, columnDefinition = "TEXT")
  private String answerText;

  public UUID getId() { return id; }
  public Order getOrder() { return order; }
  public void setOrder(Order order) { this.order = order; }
  public SitterQuestion getQuestion() { return question; }
  public void setQuestion(SitterQuestion question) { this.question = question; }
  public String getAnswerText() { return answerText; }
  public void setAnswerText(String answerText) { this.answerText = answerText; }
}
