package com.petsitter.domain.repository;

import com.petsitter.domain.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
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

    /**
     * 登入失敗 +1，累計達 {@code maxAttempts} 時同一條 UPDATE 設定鎖定時間並把次數歸零（PRD-000 AC-4）。
     * 原本 findById + save：同帳號同時打錯密碼，兩筆交易持有同一個 version → 晚到者樂觀鎖 409，
     * 且被 rollback 的那次失敗不會計入（backlog G3）。改成單一條件式 UPDATE：Postgres 列鎖讓併發的 UPDATE
     * 依序執行，READ COMMITTED 下晚到者會以最新列值重新計算，計數精確。SET 內所有運算式都讀舊列值，
     * 所以 lockedUntil 與 failedLoginAttempts 的 CASE 判斷一致。
     * 鎖定中（lockedUntil > now）的失敗不累加，否則解鎖後剩不到 maxAttempts 次額度；照樣 version + 1。
     */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE User u SET "
            + "u.lockedUntil = CASE WHEN u.failedLoginAttempts + 1 >= :maxAttempts THEN :lockUntil "
            + "ELSE u.lockedUntil END, "
            + "u.failedLoginAttempts = CASE WHEN u.failedLoginAttempts + 1 >= :maxAttempts THEN 0 "
            + "ELSE u.failedLoginAttempts + 1 END, "
            + "u.version = u.version + 1 "
            + "WHERE u.id = :userId AND (u.lockedUntil IS NULL OR u.lockedUntil <= :now)")
    int registerFailedLoginAttempt(@Param("userId") UUID userId, @Param("maxAttempts") int maxAttempts,
            @Param("now") OffsetDateTime now, @Param("lockUntil") OffsetDateTime lockUntil);
}
