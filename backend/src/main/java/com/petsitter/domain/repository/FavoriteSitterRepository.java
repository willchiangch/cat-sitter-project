package com.petsitter.domain.repository;

import com.petsitter.domain.model.FavoriteSitter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FavoriteSitterRepository extends JpaRepository<FavoriteSitter, UUID> {
    List<FavoriteSitter> findByOwnerIdAndIsDeletedFalseOrderByCreatedAtDesc(UUID ownerId);

    Optional<FavoriteSitter> findByOwnerIdAndSitterIdAndIsDeletedFalse(UUID ownerId, UUID sitterId);

    long countByOwnerIdAndIsDeletedFalse(UUID ownerId);
}
