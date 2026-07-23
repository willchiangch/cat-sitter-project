package com.petsitter.domain.repository;

import com.petsitter.domain.model.FavoriteSitter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FavoriteSitterRepository extends JpaRepository<FavoriteSitter, UUID> {
    List<FavoriteSitter> findByOwnerIdAndIsDeletedFalseOrderByCreatedAtDesc(UUID ownerId);

    Optional<FavoriteSitter> findByOwnerIdAndSitterIdAndIsDeletedFalse(UUID ownerId, UUID sitterId);

    long countByOwnerIdAndIsDeletedFalse(UUID ownerId);

    @Modifying
    @Query("UPDATE FavoriteSitter f SET f.isDeleted = true WHERE f.owner.id = :userId OR f.sitter.id = :userId")
    int softDeleteByPartyId(@Param("userId") UUID userId);
}
