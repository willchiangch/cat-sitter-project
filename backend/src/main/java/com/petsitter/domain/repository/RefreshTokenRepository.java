package com.petsitter.domain.repository;

import com.petsitter.domain.model.RefreshToken;
import com.petsitter.domain.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {
    Optional<RefreshToken> findByToken(String token);

    /**
     * 刪除該使用者所有 refresh token（SD-000 單一 session）。
     * <p>
     * 刻意寫成 bulk DELETE，不用 Spring Data 衍生刪除：衍生版本會先 SELECT 出實體再逐筆 em.remove()，
     * 兩筆交易同時登入同帳號時，晚到的那筆要刪的列已被先到者刪掉，Hibernate 收到 0 rows affected
     * → ObjectOptimisticLockingFailureException → 409 MSG_DATA_CONCURRENCY_CONFLICT（backlog F1）。
     * bulk DELETE 刪不到就是 0 筆，不會拋例外。
     */
    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM RefreshToken t WHERE t.user = :user")
    int deleteByUser(@Param("user") User user);
}
