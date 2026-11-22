package com.petsitter.domain.repository;

import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 稽核實體的 Repository 基底（SD-GLOBAL-SPEC §4.3「append-only」、NFR-003 SEC-AUDIT-3）：只有 {@code insert} 與查詢，
 * <b>不得</b>繼承 {@code CrudRepository} / {@code JpaRepository}，也不得宣告 {@code save*} / {@code delete*}
 * （{@code AuditAppendOnlyArchTest} 斷言）。MVP 只做應用層防護，不加 DB trigger（CQ-33）。
 * <p>
 * 子介面須同時直接宣告 {@code AppendOnlyInsert<實體>}：Spring Data 只從 repository 介面直接宣告的介面找 fragment 實作。
 */
@NoRepositoryBean
public interface AppendOnlyRepository<T, ID> extends Repository<T, ID>, AppendOnlyInsert<T> {

    Optional<T> findById(ID id);

    List<T> findAll();

    long count();
}
