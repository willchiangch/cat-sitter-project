package com.petsitter.application.common.idempotency;

/** 回放用的第一次結果（狀態碼 + JSON 文字；body 可為 null）。 */
record IdempotencyStoredResponse(int status, String bodyJson) {
}
