package com.petsitter.domain.repository;

import com.petsitter.domain.model.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.UUID;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    @Query("SELECT n FROM Notification n WHERE n.userId = :userId AND n.isDeleted = false " +
           "AND n.roleTarget IN :roleTargets AND (:isRead IS NULL OR n.isRead = :isRead)")
    Page<Notification> findNotifications(
            @Param("userId") UUID userId,
            @Param("roleTargets") Collection<String> roleTargets,
            @Param("isRead") Boolean isRead,
            Pageable pageable
    );

    @Query("SELECT COUNT(n) FROM Notification n WHERE n.userId = :userId AND n.isDeleted = false " +
           "AND n.roleTarget IN :roleTargets AND n.isRead = false")
    long countUnreadNotifications(
            @Param("userId") UUID userId,
            @Param("roleTargets") Collection<String> roleTargets
    );

    @Modifying
    @Query("UPDATE Notification n SET n.isRead = true, n.readAt = :readAt " +
           "WHERE n.userId = :userId AND n.isDeleted = false AND n.isRead = false AND n.roleTarget IN :roleTargets")
    int markAllAsRead(
            @Param("userId") UUID userId,
            @Param("roleTargets") Collection<String> roleTargets,
            @Param("readAt") OffsetDateTime readAt
    );

    // Native query to safely perform bulk deletion with LIMIT on db-f1-micro
    @Modifying
    @Query(value = "DELETE FROM notifications WHERE id IN (" +
           "SELECT id FROM notifications WHERE created_at < :cutoffTime " +
           "ORDER BY created_at ASC LIMIT :limit)", nativeQuery = true)
    int deleteOldNotificationsLimit(
            @Param("cutoffTime") OffsetDateTime cutoffTime,
            @Param("limit") int limit
    );
}
