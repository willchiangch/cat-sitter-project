package com.petsitter.domain.model.fulfill;

import com.petsitter.domain.model.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 行程單日 SOP 檢核項（SD-FULFILL §5.1 第 14 段，PRD-028）。{@code created_by} / {@code updated_by} 由 Service 明確寫入
 * （SITTER 寫 token 身分、SYSTEM 帶入寫 NULL），不用 auditing（SD-FULFILL §2.5 第 7 點）。
 */
@Entity
@Table(name = "visit_checklist_items")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
public class VisitChecklistItem extends AuditedEntity {

    public static final String SOURCE_SYSTEM = "SYSTEM";
    public static final String SOURCE_SITTER = "SITTER";

    @Column(name = "visit_id", nullable = false, updatable = false)
    private UUID visitId;

    @Column(nullable = false, length = 50)
    private String title;

    @Column(name = "sort_order", nullable = false)
    private short sortOrder;

    @Column(name = "is_completed", nullable = false)
    private boolean completed;

    @Column(name = "checked_at")
    private OffsetDateTime checkedAt;

    @Column(name = "created_source", nullable = false, updatable = false, length = 8)
    private String createdSource;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "updated_by")
    private UUID updatedBy;
}
