package com.petsitter.domain.model.fulfill;

import com.petsitter.domain.model.VersionedEntity;
import com.petsitter.domain.model.order.VisitStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 行程（SD-FULFILL §5.1 第 13 段）。只存 {@code orderId}，不與 {@code Order} 建 JPA 關聯（SD-FULFILL §2.5 第 2 點）。
 * 狀態欄只由 {@code application.service.fulfill} 在持有訂單鎖（或排程鎖）的交易內寫入（SD-FULFILL §3.1）。
 */
@Entity
@Table(name = "visits")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
public class Visit extends VersionedEntity {

    public static final String SOURCE_SITTER = "SITTER";
    public static final String SOURCE_SYSTEM = "SYSTEM";

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(name = "order_item_id", nullable = false, updatable = false)
    private UUID orderItemId;

    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "sitter_id", nullable = false, updatable = false)
    private UUID sitterId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VisitStatus status;

    @Column(name = "plan_id", nullable = false, updatable = false)
    private UUID planId;

    @Column(name = "booking_plan_title", nullable = false, updatable = false)
    private String bookingPlanTitle;

    @Column(name = "service_date", nullable = false, updatable = false)
    private LocalDate serviceDate;

    @Column(name = "sequence_in_day", nullable = false, updatable = false)
    private short sequenceInDay;

    @Column(name = "slots_in_day", nullable = false, updatable = false)
    private short slotsInDay;

    @Column(name = "custom_name", length = 20)
    private String customName;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "completion_source", length = 8)
    private String completionSource;

    @Column(name = "cancelled_at")
    private OffsetDateTime cancelledAt;

    @Column(name = "closed_at")
    private OffsetDateTime closedAt;
}
