package com.petsitter.infrastructure.lock;

import com.petsitter.application.exception.OrderBusyException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 列鎖共用作法（SD-GLOBAL-SPEC §2.3、SD-ORDER §3.6.1 / §11.4「鎖 SQL」）：以 native SQL 執行
 * {@code SET LOCAL lock_timeout = '3s'} 與 {@code SELECT ... FOR NO KEY UPDATE}，不依賴 JPA {@code PESSIMISTIC_WRITE}
 * （依 dialect 版本可能變成 {@code FOR UPDATE}，與子表 FK 檢查的 {@code FOR KEY SHARE} 互斥）。
 * <p>
 * 給 B 階段 {@code OrderLockService.lockForTransition / tryLockForCron} 使用；該 Service 負責 404、鎖後 {@code refresh}
 * 與 {@code LockedOrder} 檢視。
 * <ul>
 *   <li>{@code lock_timeout} 以 {@code SET LOCAL} 設定，對整個交易有效（鎖後的語句也可能逾時，SQLSTATE 55P03
 *       由模組 advice 對映 {@code ORDER_BUSY}）；交易結束自動還原，Transaction Pooler 下安全。</li>
 *   <li>不使用 session 級 {@code SET} / {@code pg_advisory_lock}。</li>
 *   <li>透過 {@link JdbcTemplate} 執行：與 JPA 共用同一交易連線（{@code JpaTransactionManager} 暴露 JDBC 連線），
 *       且 Spring 會把 55P03 / 40P01 轉成 {@code CannotAcquireLockException} / {@code PessimisticLockingFailureException}。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class RowLockSupport {

    /** SD-ORDER §3.6.1：鎖等待上限 3 秒。 */
    public static final String LOCK_TIMEOUT = "3s";

    private final JdbcTemplate jdbcTemplate;

    /**
     * 鎖定 {@code target} 中 id 的那一列（{@code FOR NO KEY UPDATE}）。
     *
     * @return 列存在且已鎖住 → true；列不存在 → false（呼叫端決定 404）
     * @throws OrderBusyException 鎖等待逾時（55P03）或死結（40P01）
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lockForNoKeyUpdate(LockTarget target, UUID id) {
        try {
            jdbcTemplate.execute("SET LOCAL lock_timeout = '" + LOCK_TIMEOUT + "'");
            List<UUID> rows = jdbcTemplate.queryForList(
                    "SELECT id FROM " + target.table() + " WHERE id = ? FOR NO KEY UPDATE", UUID.class, id);
            return !rows.isEmpty();
        } catch (PessimisticLockingFailureException e) {
            throw new OrderBusyException(e);
        }
    }

    /**
     * 排程用（SD-ORDER §3.6.1 {@code tryLockForCron}）：{@code FOR NO KEY UPDATE SKIP LOCKED}，鎖不到或不存在都回 false，不等待。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean tryLockSkipLocked(LockTarget target, UUID id) {
        List<UUID> rows = jdbcTemplate.queryForList(
                "SELECT id FROM " + target.table() + " WHERE id = ? FOR NO KEY UPDATE SKIP LOCKED", UUID.class, id);
        return !rows.isEmpty();
    }
}
