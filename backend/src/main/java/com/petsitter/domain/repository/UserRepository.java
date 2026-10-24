package com.petsitter.domain.repository;

import com.petsitter.domain.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);

    /**
     * 登入成功後清除失敗次數與鎖定（PRD-000 AC-4）。用 bulk UPDATE 而非 save(user)，
     * 同帳號併發登入時不會因兩筆交易持有同一個 version 而樂觀鎖衝突（backlog F1）；手動把 version + 1，
     * 讓其他持有舊 version 的寫入照樣被樂觀鎖擋下。
     */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE User u SET u.failedLoginAttempts = 0, u.lockedUntil = null, u.version = u.version + 1 "
            + "WHERE u.id = :userId AND (u.failedLoginAttempts > 0 OR u.lockedUntil IS NOT NULL)")
    int resetFailedLoginAttempts(@Param("userId") UUID userId);
}
