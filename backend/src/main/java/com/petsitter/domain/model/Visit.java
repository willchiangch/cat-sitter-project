package com.petsitter.domain.model;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.OffsetDateTime;

@Entity
@Table(name = "visits")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class Visit extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(nullable = false)
    private String status; // PENDING, IN_PROGRESS, DONE, CLOSED_BY_SYSTEM

    @Column(name = "plan_id", nullable = false)
    private java.util.UUID planId;

    @Column(name = "snapshot_plan_title", nullable = false)
    private String snapshotPlanTitle;

    @Column(name = "scheduled_at", nullable = false)
    private OffsetDateTime scheduledAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;
}
