package com.petsitter.domain.repository;

import com.petsitter.domain.model.NotificationPreference;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface NotificationPreferenceRepository extends JpaRepository<NotificationPreference, UUID> {

    Optional<NotificationPreference> findByUserIdAndCategoryAndIsDeletedFalse(UUID userId, String category);

    List<NotificationPreference> findByUserIdAndIsDeletedFalse(UUID userId);
}
