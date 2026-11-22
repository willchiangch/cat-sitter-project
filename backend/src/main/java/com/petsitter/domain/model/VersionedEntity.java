package com.petsitter.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * {@link AuditedEntity} + {@code @Version}（SD-GLOBAL-SPEC §2.5）。只給有並行寫入的狀態機或計數實體
 * （例：{@code orders}、{@code payments}、{@code modification_requests}、{@code visits}）。
 * <p>
 * {@code version} 是最後防線：不接受客戶端 version、無 setter；衝突（{@code ObjectOptimisticLockingFailureException}）
 * 由模組 advice 對映 409 {@code ORDER_BUSY} + {@code Retry-After: 1}（SD-ORDER §3.6.1）。
 */
@MappedSuperclass
@Getter
@SuperBuilder
@NoArgsConstructor
public abstract class VersionedEntity extends AuditedEntity {

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;
}
