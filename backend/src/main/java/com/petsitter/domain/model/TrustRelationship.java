package com.petsitter.domain.model;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

/**
 * PRD-010: 保母間的信任圈關係，須經雙方同意才成立 (雙向同意制)
 */
@Entity
@Table(name = "trust_relationships")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class TrustRelationship extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requester_id", nullable = false)
    private User requester;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_id", nullable = false)
    private User target;

    @Builder.Default
    @Column(nullable = false)
    private String status = "PENDING"; // PENDING, ACCEPTED
}
