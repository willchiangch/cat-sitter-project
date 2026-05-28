package com.petsitter.domain.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "gatekeeper_rules")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GatekeeperRule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "sitter_id", nullable = false)
    private UUID sitterId;

    @Column(name = "rule_type", nullable = false, length = 50)
    private String ruleType; // BLACK, WHITE, NO_QUESTIONNAIRE

    @Column(name = "scope_type", nullable = false, length = 50)
    private String scopeType; // GLOBAL, PLAN

    @Column(name = "plan_id")
    private UUID planId;

    @Column(name = "target_user_id", nullable = false)
    private UUID targetUserId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
