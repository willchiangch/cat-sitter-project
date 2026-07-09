package com.petsitter.domain.repository;

import com.petsitter.domain.model.ForbiddenKeyword;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ForbiddenKeywordRepository extends JpaRepository<ForbiddenKeyword, UUID> {
    Optional<ForbiddenKeyword> findByKeyword(String keyword);
    boolean existsByKeyword(String keyword);

    // 呼叫端 (SitterPublicProfileServiceImpl) 保證 q 不為 null/blank 才會呼叫這個查詢
    @Query("SELECT f FROM ForbiddenKeyword f WHERE LOWER(f.keyword) LIKE LOWER(CONCAT('%', :q, '%'))")
    Page<ForbiddenKeyword> findByKeywordContaining(@Param("q") String q, Pageable pageable);
}
