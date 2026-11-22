package com.petsitter.domain.repository;

/**
 * {@link AppendOnlyRepository} 的寫入 fragment：只能新增（{@code EntityManager.persist}），不能 merge / 更新既有列。
 */
public interface AppendOnlyInsert<T> {

    <S extends T> S insert(S entity);
}
