package com.petsitter.domain.model.fulfill;

import com.petsitter.domain.model.VersionedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 照護日誌（SD-FULFILL §5.1 第 15 段）。建立者 / 更新者由 Service 寫入（不經 auditing）。 */
@Entity
@Table(name = "visit_service_reports")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
public class VisitServiceReport extends VersionedEntity {

    @Column(name = "visit_id", nullable = false, unique = true, updatable = false)
    private UUID visitId;

    @Column(nullable = false)
    private String status; // DRAFT, SUBMITTED

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(name = "submitted_at")
    private OffsetDateTime submittedAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "updated_by")
    private UUID updatedBy;
}
