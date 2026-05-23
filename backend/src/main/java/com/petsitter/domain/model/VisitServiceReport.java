package com.petsitter.domain.model;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "visit_service_reports")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class VisitServiceReport extends BaseEntity {

    @Column(name = "visit_id", nullable = false, unique = true)
    private UUID visitId;

    @Column(nullable = false)
    private String status; // DRAFT, SUBMITTED

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(name = "submitted_at")
    private OffsetDateTime submittedAt;
}
