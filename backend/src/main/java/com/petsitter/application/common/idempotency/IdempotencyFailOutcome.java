package com.petsitter.application.common.idempotency;

/** {@link IdempotencyService#fail} 的結果。 */
public enum IdempotencyFailOutcome {
    /** 可記錄的業務錯誤（409 / 422，不含 ORDER_BUSY、IDEMPOTENCY_IN_PROGRESS）：紀錄改 COMPLETED 並存錯誤回應 */
    RECORDED,
    /** 5xx、ORDER_BUSY、STORAGE_UNAVAILABLE 等：刪除紀錄（本請求仍持有 token） */
    DELETED,
    /** 帶 token 的 UPDATE / DELETE 影響 0 列：已被接手或交易 B 其實已提交，不動任何東西 */
    NOT_HELD,
    /** 409 IDEMPOTENCY_IN_PROGRESS（被接手）：不記錄、不刪除 */
    UNTOUCHED
}
