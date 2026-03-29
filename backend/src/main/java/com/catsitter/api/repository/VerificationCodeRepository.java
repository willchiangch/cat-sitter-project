package com.catsitter.api.repository;

import com.catsitter.api.entity.Account;
import com.catsitter.api.entity.VerificationCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface VerificationCodeRepository extends JpaRepository<VerificationCode, UUID> {
    Optional<VerificationCode> findByAccountAndCode(Account account, String code);
    void deleteByAccount(Account account);
}
