package com.petsitter.domain.model;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

@Entity
@Table(name = "service_plans")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class ServicePlan extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sitter_id", nullable = false)
    private User sitter;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private Integer dailyCapacity; // 每日最大接單量

    @Column(nullable = false)
    private Long price;

    // --- PRD-013 媒體保留規則 ---
    @Builder.Default
    @Column(name = "media_retention_days", nullable = false)
    private Integer mediaRetentionDays = 30;

    @Builder.Default
    @Column(name = "max_photos", nullable = false)
    private Integer maxPhotos = 10;

    @Builder.Default
    @Column(name = "max_video_seconds", nullable = false)
    private Integer maxVideoSeconds = 0;
}
