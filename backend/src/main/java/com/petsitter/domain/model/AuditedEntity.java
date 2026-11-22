package com.petsitter.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 重建模組的實體基底（SD-GLOBAL-SPEC §2.5）：{@code id}（UUID）、{@code created_at}、{@code updated_at}。
 * <p>
 * 與舊 {@link BaseEntity} 的差異：沒有 {@code is_deleted}（不做軟刪除）、沒有 {@code @CreatedBy} /
 * {@code @LastModifiedBy}（建立者欄位由 Service 明確寫入，系統建立寫 NULL）、沒有 {@code @Version}
 * （需要的實體改繼承 {@link VersionedEntity}）。兩個基底並存：未重建模組繼續用 {@link BaseEntity}，
 * 重建時改繼承本類別並在同一次發布的遷移刪除 {@code is_deleted} / {@code created_by} / {@code updated_by}。
 * 不可變的明細 / 稽核列（{@code order_items}、{@code order_logs}、{@code idempotency_records} 等）不繼承任何基底。
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
@Getter
@SuperBuilder
@NoArgsConstructor
public abstract class AuditedEntity {

    /** 未指定時產生隨機 UUID；Service 可預先指定（例：payments，SD-ORDER §8.2 AAD），見 {@link AssignableUuid}。 */
    @Id
    @AssignableUuid
    private UUID id;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
