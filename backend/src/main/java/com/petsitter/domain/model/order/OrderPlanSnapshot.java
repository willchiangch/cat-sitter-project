package com.petsitter.domain.model.order;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.UUID;

/** 報價時各方案單價與名稱快照（SD-ORDER §5.1.1 第 6 段，CQ-20）。 */
@Entity
@Table(name = "order_plan_snapshots")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderPlanSnapshot {

    @EmbeddedId
    private Key id;

    @Column(name = "plan_title", nullable = false, updatable = false)
    private String planTitle;

    @Column(name = "unit_price", nullable = false, updatable = false)
    private int unitPrice;

    @Column(name = "snapshotted_at", nullable = false, updatable = false)
    private OffsetDateTime snapshottedAt;

    public UUID getOrderId() {
        return id.getOrderId();
    }

    public UUID getPlanId() {
        return id.getPlanId();
    }

    @Embeddable
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        @Column(name = "order_id", nullable = false)
        private UUID orderId;
        @Column(name = "plan_id", nullable = false)
        private UUID planId;
    }
}
