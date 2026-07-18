package com.petsitter.domain.repository;

import com.petsitter.domain.model.TrustRelationship;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TrustRelationshipRepository extends JpaRepository<TrustRelationship, UUID> {

    @Query("SELECT r FROM TrustRelationship r WHERE r.isDeleted = false AND r.status = 'ACCEPTED' " +
           "AND (r.requester.id = :sitterId OR r.target.id = :sitterId)")
    List<TrustRelationship> findAcceptedBySitterId(@Param("sitterId") UUID sitterId);

    List<TrustRelationship> findByTargetIdAndStatusAndIsDeletedFalse(UUID targetId, String status);

    List<TrustRelationship> findByRequesterIdAndStatusAndIsDeletedFalse(UUID requesterId, String status);

    @Query("SELECT r FROM TrustRelationship r WHERE r.isDeleted = false AND " +
           "((r.requester.id = :userA AND r.target.id = :userB) OR (r.requester.id = :userB AND r.target.id = :userA))")
    Optional<TrustRelationship> findBetween(@Param("userA") UUID userA, @Param("userB") UUID userB);
}
