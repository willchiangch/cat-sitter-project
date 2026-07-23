package com.petsitter.domain.repository;

import com.petsitter.domain.model.RegistrationOtp;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface RegistrationOtpRepository extends JpaRepository<RegistrationOtp, UUID> {
    Optional<RegistrationOtp> findByEmail(String email);

    void deleteByEmail(String email);
}
