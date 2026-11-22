package com.petsitter.application.common.idempotency;

import java.util.UUID;

/**
 * 認領結果：紀錄 id 與本請求持有的 {@code claim_token}（每次認領或接手都換新）。
 * 之後的 complete / fail 一律以 {@code id + status='PROCESSING' + claim_token} 為條件。
 *
 * @param recordId   {@code idempotency_records.id}；E11 以此決定 GCS 物件鍵（SD-ORDER §3.6.6）
 * @param takenOver  true = 接手了 {@code locked_until} 已過期的處理中紀錄
 */
public record IdempotencyClaim(UUID recordId, UUID claimToken, boolean takenOver, IdempotencyContext context) {
}
