package com.petsitter.domain.model.order;

import com.petsitter.domain.model.VersionedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.util.UUID;

/**
 * 訂單層媒體額度快照（SD-ORDER §5.1.1 第 7 段）：{@code plan_tier}、{@code max_photos}、{@code max_videos}、
 * {@code media_retention_days}（-1 = 永久）。保留 version（展延保留期限會改寫）。
 */
@Entity
@Table(name = "order_snapshots")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
public class OrderSnapshot extends VersionedEntity {

    @Column(name = "order_id", nullable = false, updatable = false, unique = true)
    private UUID orderId;

    @Column(name = "plan_tier", nullable = false)
    private String planTier;

    @Column(name = "max_photos", nullable = false)
    private int maxPhotos;

    @Column(name = "max_videos", nullable = false)
    private int maxVideos;

    @Column(name = "media_retention_days", nullable = false)
    private int mediaRetentionDays;
}
