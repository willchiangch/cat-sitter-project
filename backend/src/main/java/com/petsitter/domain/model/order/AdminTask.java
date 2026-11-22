package com.petsitter.domain.model.order;

import com.petsitter.domain.model.VersionedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.OffsetDateTime;
import java.util.UUID;

/** ADMIN 待辦（SD-ORDER §3.5、§5.1.1 第 11 段）。 */
@Entity
@Table(name = "admin_tasks")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
public class AdminTask extends VersionedEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private AdminTaskType type;

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(name = "payment_id", updatable = false)
    private UUID paymentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AdminTaskStatus status;

    @Column(name = "opened_by", updatable = false)
    private UUID openedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "opened_by_role", nullable = false, updatable = false)
    private ActorRole openedByRole;

    @Enumerated(EnumType.STRING)
    @Column
    private AdminTaskResolution resolution;

    @Column(name = "resolved_by")
    private UUID resolvedBy;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;
}
