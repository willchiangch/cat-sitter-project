package com.petsitter.domain.model;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.OffsetDateTime;

@Entity
@Table(name = "subscriptions")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class Subscription extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sitter_id", nullable = false, unique = true)
    private User sitter;

    @Column(name = "plan_tier", nullable = false)
    private String planTier; // FREE, BASIC, PRO, ULTIMATE (共四級)

    @Builder.Default
    @Column(name = "monthly_order_count", nullable = false)
    private Integer monthlyOrderCount = 0;

    @Column(name = "expired_at")
    private OffsetDateTime expiredAt;
}
