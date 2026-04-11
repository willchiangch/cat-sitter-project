package com.catsitter.api.entity;

import com.catsitter.api.entity.common.AuditableEntity;
import com.catsitter.api.entity.enums.VisitStatus;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "visits")
@org.hibernate.annotations.SQLDelete(sql = "UPDATE visits SET deleted_at = NOW() WHERE id = ?")
@org.hibernate.annotations.SQLRestriction("deleted_at IS NULL")
public class Visit extends AuditableEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @NotNull
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "order_id", nullable = false)
  private Order order;

  @NotNull
  @Column(name = "visit_start_time", nullable = false)
  private OffsetDateTime visitStartTime;

  @NotNull
  @Column(name = "visit_end_time", nullable = false)
  private OffsetDateTime visitEndTime;

  @NotNull
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 50)
  private VisitStatus status;

  @Column(name = "sitter_notes", columnDefinition = "TEXT")
  private String sitterNotes;

  @Column(name = "calendar_event_id")
  private String calendarEventId;

  public UUID getId() { return id; }
  public Order getOrder() { return order; }
  public void setOrder(Order order) { this.order = order; }
  public OffsetDateTime getVisitStartTime() { return visitStartTime; }
  public void setVisitStartTime(OffsetDateTime visitStartTime) { this.visitStartTime = visitStartTime; }
  public OffsetDateTime getVisitEndTime() { return visitEndTime; }
  public void setVisitEndTime(OffsetDateTime visitEndTime) { this.visitEndTime = visitEndTime; }
  public VisitStatus getStatus() { return status; }
  public void setStatus(VisitStatus status) { this.status = status; }
  public String getSitterNotes() { return sitterNotes; }
  public void setSitterNotes(String sitterNotes) { this.sitterNotes = sitterNotes; }
  public String getCalendarEventId() { return calendarEventId; }
  public void setCalendarEventId(String calendarEventId) { this.calendarEventId = calendarEventId; }
}
