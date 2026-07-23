package com.petsitter.domain.repository;

import com.petsitter.domain.model.WebAuthnChallenge;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface WebAuthnChallengeRepository extends JpaRepository<WebAuthnChallenge, UUID> {
    Optional<WebAuthnChallenge> findTopByUserIdAndChallengeTypeOrderByCreatedAtDesc(UUID userId, String challengeType);

    void deleteByUserIdAndChallengeType(UUID userId, String challengeType);
}
