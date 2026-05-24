package com.petsitter.domain.model;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDate;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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

    // --- SD-003 自訂方案擴充欄位 ---
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "default_tasks", nullable = false)
    private List<String> defaultTasks = List.of();

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "applicable_pet_types", nullable = false)
    private List<String> applicablePetTypes = List.of();

    @Column(name = "description")
    private String description;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Builder.Default
    @Column(name = "is_restricted", nullable = false)
    private boolean isRestricted = false;

    @Builder.Default
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;
}
