package com.petsitter.application.common.idempotency;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code idempotency_records} 的唯一存取點（SD-GLOBAL-SPEC §2.4：只准本套件依賴，ArchUnit 守護）。
 * <p>
 * 以 {@link JdbcTemplate} 實作：認領需要 {@code INSERT ... ON CONFLICT ... RETURNING}，且所有寫入都帶
 * {@code claim_token} 條件；JPA 實體反而會引入一級快取與 flush 順序問題。在 JPA 交易內呼叫時共用同一條連線。
 */
@Repository
@RequiredArgsConstructor
class IdempotencyRecordRepository {

    static final String PROCESSING = "PROCESSING";
    static final String COMPLETED = "COMPLETED";

    private final JdbcTemplate jdbcTemplate;

    record Row(UUID id, String requestHash, String status, UUID claimToken, OffsetDateTime lockedUntil,
               Integer responseStatus, String responseBody, OffsetDateTime expiresAt) {
    }

    record Claimed(UUID id, UUID claimToken) {
    }

    Optional<Row> findByScope(UUID userId, String scope, String key) {
        List<Row> rows = jdbcTemplate.query("""
                SELECT id, request_hash, status, claim_token, locked_until, response_status,
                       response_body::text AS response_body, expires_at
                  FROM idempotency_records
                 WHERE user_id = ? AND scope = ? AND idempotency_key = ?
                """, (rs, i) -> new Row(
                rs.getObject("id", UUID.class),
                rs.getString("request_hash"),
                rs.getString("status"),
                rs.getObject("claim_token", UUID.class),
                rs.getObject("locked_until", OffsetDateTime.class),
                (Integer) rs.getObject("response_status", Integer.class),
                rs.getString("response_body"),
                rs.getObject("expires_at", OffsetDateTime.class)), userId, scope, key);
        return rows.stream().findFirst();
    }

    /**
     * 段 1：{@code INSERT ... ON CONFLICT DO UPDATE ... WHERE 舊列已過期}。新列或取代過期列時回傳（id 也換新，
     * 避免沿用舊 id 的 GCS 物件鍵）；衝突且舊列未過期時回 empty。
     */
    Optional<Claimed> insertOrReplaceExpired(UUID newId, IdempotencyContext ctx, UUID claimToken,
                                             OffsetDateTime now, OffsetDateTime lockedUntil, OffsetDateTime expiresAt) {
        List<Claimed> rows = jdbcTemplate.query("""
                INSERT INTO idempotency_records
                       (id, user_id, scope, idempotency_key, request_hash, status, claim_token,
                        locked_until, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, 'PROCESSING', ?, ?, ?, ?)
                ON CONFLICT (user_id, scope, idempotency_key) DO UPDATE
                   SET id = EXCLUDED.id,
                       request_hash = EXCLUDED.request_hash,
                       status = 'PROCESSING',
                       claim_token = EXCLUDED.claim_token,
                       locked_until = EXCLUDED.locked_until,
                       response_status = NULL,
                       response_body = NULL,
                       created_at = EXCLUDED.created_at,
                       expires_at = EXCLUDED.expires_at
                 WHERE idempotency_records.expires_at <= ?
                RETURNING id, claim_token
                """, (rs, i) -> new Claimed(rs.getObject("id", UUID.class), rs.getObject("claim_token", UUID.class)),
                newId, ctx.userId(), ctx.scope(), ctx.key(), ctx.requestHash(), claimToken,
                lockedUntil, now, expiresAt, now);
        return rows.stream().findFirst();
    }

    /** 接手：只有 {@code locked_until} 已過的處理中紀錄可被接手，且只有一個請求能成功。 */
    boolean takeOver(UUID id, UUID newToken, OffsetDateTime now, OffsetDateTime lockedUntil) {
        return jdbcTemplate.update("""
                UPDATE idempotency_records
                   SET claim_token = ?, locked_until = ?
                 WHERE id = ? AND status = 'PROCESSING' AND locked_until <= ?
                """, newToken, lockedUntil, id, now) == 1;
    }

    /** 段 3 / 失敗段記錄：改 COMPLETED 並存回應（帶 token 條件）。 */
    boolean markCompleted(UUID id, UUID token, int responseStatus, String responseBodyJson) {
        return jdbcTemplate.update("""
                UPDATE idempotency_records
                   SET status = 'COMPLETED', response_status = ?, response_body = CAST(? AS jsonb), locked_until = NULL
                 WHERE id = ? AND status = 'PROCESSING' AND claim_token = ?
                """, responseStatus, responseBodyJson, id, token) == 1;
    }

    /** 失敗段刪除（帶 token 條件）：commit 結果不明時，若交易 B 已提交則影響 0 列。 */
    boolean deleteHeld(UUID id, UUID token) {
        return jdbcTemplate.update("""
                DELETE FROM idempotency_records
                 WHERE id = ? AND status = 'PROCESSING' AND claim_token = ?
                """, id, token) == 1;
    }

    /** C9：刪除一批已過期紀錄（{@code expires_at < now}），回傳刪除筆數。 */
    int deleteExpiredBatch(OffsetDateTime now, int limit) {
        return jdbcTemplate.update("""
                DELETE FROM idempotency_records WHERE id IN (
                  SELECT id FROM idempotency_records WHERE expires_at < ? ORDER BY expires_at LIMIT ?)
                """, now, limit);
    }

    /** C10：該紀錄是否仍為 PROCESSING（處理中的上傳物件不可清除）。 */
    boolean isProcessing(UUID id) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM idempotency_records WHERE id = ? AND status = 'PROCESSING'", Integer.class, id);
        return n != null && n > 0;
    }
}
