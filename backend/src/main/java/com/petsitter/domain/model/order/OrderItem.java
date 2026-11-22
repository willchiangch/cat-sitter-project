package com.petsitter.domain.model.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** 預約群組（BOOKING）與變更新增項目（MODIFICATION），建立後不改（SD-ORDER §5.1.1 第 3 段）。不繼承基底。 */
@Entity
@Table(name = "order_items")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private OrderItemSource source;

    @Column(name = "group_index", updatable = false)
    private Short groupIndex;

    @Column(name = "modification_request_id", updatable = false)
    private UUID modificationRequestId;

    @Column(name = "plan_id", nullable = false, updatable = false)
    private UUID planId;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "service_dates", nullable = false, updatable = false, columnDefinition = "date[]")
    private LocalDate[] serviceDates;

    @Column(name = "daily_count", nullable = false, updatable = false)
    private short dailyCount;

    @Column(name = "booked_unit_price", nullable = false, updatable = false)
    private int bookedUnitPrice;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
