package com.petsitter.infrastructure.lock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdvisoryLockService {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 獲取事務級別的 Advisory Lock。
     * 強制要求呼叫方必須已開啟 Transaction，否則拋錯。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void acquireLocks(List<Long> sortedLockKeys) {
        for (Long key : sortedLockKeys) {
            log.debug("Acquiring pg_advisory_xact_lock for key: {}", key);
            // pg_advisory_xact_lock 沒有回傳值 (void)
            jdbcTemplate.execute("SELECT pg_advisory_xact_lock(" + key + ")");
        }
    }

    /**
     * 將保母 ID 與日期轉換為穩定的 64-bit BIGINT 作為 Lock Key。
     */
    public static long generateLockKey(UUID sitterId, LocalDate date) {
        int sitterHash = sitterId.hashCode();
        int dateHash = date.hashCode();
        // 將兩個 32-bit int 合併為一個 64-bit long
        return (((long) sitterHash) << 32) | (dateHash & 0xffffffffL);
    }
}
