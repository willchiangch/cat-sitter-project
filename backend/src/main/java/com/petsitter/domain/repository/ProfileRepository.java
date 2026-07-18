package com.petsitter.domain.repository;

import com.petsitter.domain.model.Profile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProfileRepository extends JpaRepository<Profile, UUID> {
    Optional<Profile> findByUserIdAndType(UUID userId, String type);

    @Query("SELECT p FROM Profile p WHERE p.type = 'SITTER' ORDER BY p.trustScore ASC")
    List<Profile> findAllSitterProfilesOrderByTrustScoreAsc();
}
