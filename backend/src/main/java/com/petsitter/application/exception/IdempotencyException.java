package com.petsitter.application.exception;

/**
 * 冪等認領 / 完成失敗（SD-GLOBAL-SPEC §2.4；SD-ORDER §6.4）：
 * {@link Kind#REUSED} → 422 {@code IDEMPOTENCY_KEY_REUSED}；{@link Kind#IN_PROGRESS} → 409 {@code IDEMPOTENCY_IN_PROGRESS}。
 */
public class IdempotencyException extends RuntimeException {

    public enum Kind {
        REUSED(CommonErrorCode.IDEMPOTENCY_KEY_REUSED),
        IN_PROGRESS(CommonErrorCode.IDEMPOTENCY_IN_PROGRESS);

        private final CommonErrorCode code;

        Kind(CommonErrorCode code) {
            this.code = code;
        }

        public CommonErrorCode code() {
            return code;
        }
    }

    private final Kind kind;

    public IdempotencyException(Kind kind) {
        super(kind.code().defaultMessage());
        this.kind = kind;
    }

    public Kind getKind() {
        return kind;
    }
}
