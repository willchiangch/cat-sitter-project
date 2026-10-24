package com.petsitter.infrastructure.lock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdvisoryLockService {

    private final JdbcTemplate jdbcTemplate;

    /** 單一 session 鎖的命名空間（pg_advisory_xact_lock(int, int) 的第一個參數）。 */
    static final int USER_SESSION_LOCK_NAMESPACE = 7001;

    /**
     * SD-000 單一 session：序列化同一使用者的「刪舊 refresh token → 建新 refresh token」（backlog F1）。
     * refresh_tokens.user_id 沒有唯一約束，若只靠 bulk DELETE，兩筆併發交易各自刪完（看不到對方未提交的新列）
     * 再各自 INSERT，最後會留下兩筆；鎖住後，後到者的 DELETE 會看到先到者已提交的新 token 並刪掉 → 後登入者覆蓋。
     * 用 (int, int) 雙參數版本，鍵空間與其他單一 bigint 鍵的 advisory lock 不重疊；
     * 第二個參數取 userId hashCode，碰撞只會讓兩個不同帳號短暫排隊，不影響正確性。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void acquireUserSessionLock(UUID userId) {
        jdbcTemplate.execute("SELECT pg_advisory_xact_lock(" + USER_SESSION_LOCK_NAMESPACE + ", "
                + userId.hashCode() + ")");
    }
}
