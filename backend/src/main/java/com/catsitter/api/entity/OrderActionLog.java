package com.catsitter.api.entity;

import com.catsitter.api.entity.common.AuditableEntity;
import com.catsitter.api.entity.enums.ActionType;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

@Entity
@Table(name = "order_action_logs")
public class OrderActionLog extends AuditableEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @NotNull
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "order_id", nullable = false)
  private Order order;

  /** nullable：純訂單異動時為空 */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "visit_id")
  private Visit visit;

  /** nullable：系統自動觸發時為空 */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "actor_profile_id")
  private Profile actorProfile;

  @NotNull
  @Enumerated(EnumType.STRING)
  @Column(name = "action_type", nullable = false, length = 50)
  private ActionType actionType;

  @Column(name = "previous_status", length = 50)
  private String previousStatus;

  /** nullable：非狀態轉移類型的 action 可為空 */
  @Column(name = "new_status", length = 50)
  private String newStatus;

  /** 附加資訊，如護照欄位異動 {"field": "medical_notes", "old": "...", "new": "..."} */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private String metadata;

  public UUID getId() { return id; }
  public Order getOrder() { return order; }
  public void setOrder(Order order) { this.order = order; }
  public Visit getVisit() { return visit; }
  public void setVisit(Visit visit) { this.visit = visit; }
  public Profile getActorProfile() { return actorProfile; }
  public void setActorProfile(Profile actorProfile) { this.actorProfile = actorProfile; }
  public ActionType getActionType() { return actionType; }
  public void setActionType(ActionType actionType) { this.actionType = actionType; }
  public String getPreviousStatus() { return previousStatus; }
  public void setPreviousStatus(String previousStatus) { this.previousStatus = previousStatus; }
  public String getNewStatus() { return newStatus; }
  public void setNewStatus(String newStatus) { this.newStatus = newStatus; }
  public String getMetadata() { return metadata; }
  public void setMetadata(String metadata) { this.metadata = metadata; }
}
