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
    private Long price;

    @Builder.Default
    @Column(name = "duration_minutes", nullable = false)
    private Integer durationMinutes = 60;

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

    // 下架/上架狀態 (與 isDeleted 分離；isDeleted 保留供未來稽核用實體刪除用途)
    @Builder.Default
    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    /**
     * 日期是否落在方案「可預訂日期區間」(PRD-003 B / SD-003 §3)：startDate、endDate 皆為 null = 常態開放；
     * 只設一端時另一端不限。預約送單 (BookingService) 與訂單變更新增日期 (ModificationService) 共用
     */
    public boolean isBookableOn(LocalDate date) {
        if (startDate != null && date.isBefore(startDate)) {
            return false;
        }
        return endDate == null || !date.isAfter(endDate);
    }
}
