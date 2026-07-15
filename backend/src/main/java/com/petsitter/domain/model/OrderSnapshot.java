package com.petsitter.domain.model;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;

@Entity
@Table(name = "order_snapshots")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class OrderSnapshot extends BaseEntity {

    // PRD-008/PRD-013 只定義了照片張數(maxPhotos)與影片時長(maxVideoSeconds)兩個方案可調欄位，
    // 沒有「影片支數」的分級規格，故建立快照時一律套用這個平台固定值，而非做成方案可設定欄位
    public static final int DEFAULT_MAX_VIDEOS = 5;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false, unique = true)
    private Order order;

    // --- 財務快照 (SD-006 / SD-016) ---
    @Column(name = "snapshot_plan_title", nullable = false)
    private String snapshotPlanTitle; // 當時方案名稱

    @Column(name = "snapshot_unit_price", nullable = false)
    private Integer snapshotUnitPrice; // 當時方案單價 (退款計算基準)

    @Column(name = "snapshot_original_total", nullable = false)
    private Integer snapshotOriginalTotal;

    @Column(name = "adjustment_amount", nullable = false)
    private Integer adjustmentAmount;

    // --- 媒體規則快照 (PRD-013) ---
    @Column(name = "media_retention_days", nullable = false)
    private Integer mediaRetentionDays;

    @Column(name = "max_photos", nullable = false)
    private Integer maxPhotos;

    @Builder.Default
    @Column(name = "max_videos", nullable = false)
    private Integer maxVideos = 0;

    @Column(name = "max_video_seconds", nullable = false)
    private Integer maxVideoSeconds;

    @Builder.Default
    @Column(name = "plan_tier", nullable = false)
    private String planTier = "FREE";

    // --- 完整內容快照 (JSONB) ---
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private java.util.List<OrderItem> snapshotData;
}
