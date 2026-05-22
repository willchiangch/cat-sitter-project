package com.petsitter.domain.repository;

import com.petsitter.domain.model.IdempotencyKey;
import com.petsitter.domain.model.IdempotencyKeyId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;

@Repository
public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, IdempotencyKeyId> {
    @Modifying
    @Query("DELETE FROM IdempotencyKey i WHERE i.createdAt < :olderThan")
    int deleteOldKeys(@Param("olderThan") OffsetDateTime olderThan);
}
