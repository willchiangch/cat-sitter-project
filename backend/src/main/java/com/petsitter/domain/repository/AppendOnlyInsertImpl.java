package com.petsitter.domain.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link AppendOnlyInsert} 實作（Spring Data repository fragment，依命名慣例自動套用到所有 {@link AppendOnlyRepository}）。
 * 交易傳播 REQUIRED：同交易稽核的 {@code MANDATORY} 由呼叫端（{@code OrderLogWriter}、{@code AuditLogService}）宣告。
 */
public class AppendOnlyInsertImpl<T> implements AppendOnlyInsert<T> {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @Transactional
    public <S extends T> S insert(S entity) {
        entityManager.persist(entity);
        return entity;
    }
}
