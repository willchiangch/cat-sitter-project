package com.petsitter.domain.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.util.Map;

import lombok.experimental.SuperBuilder;

@Entity
@Table(name = "order_snapshots")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class OrderSnapshot extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false, unique = true)
    private Order order;

    @Column(name = "snapshot_original_total", nullable = false)
    private Integer snapshotOriginalTotal;

    @Column(name = "adjustment_amount", nullable = false)
    private Integer adjustmentAmount;

    @Column(name = "media_retention_days", nullable = false)
    private Integer mediaRetentionDays;

    @Column(name = "max_photos", nullable = false)
    private Integer maxPhotos;

    @Column(name = "max_video_seconds", nullable = false)
    private Integer maxVideoSeconds;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "snapshot_data", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> snapshotData;
}
