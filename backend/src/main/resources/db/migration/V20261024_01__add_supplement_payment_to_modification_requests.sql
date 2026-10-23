-- PRD-016 C.3 / SD-016 §4.2：訂單變更補款的憑證與核帳紀錄獨立存在變更請求上，
-- 不再覆寫 orders 上首次付款的 payment_proof_url / payment_proof_last_five /
-- payment_idempotency_key / disclaimer_agreed_at / paid_at。
ALTER TABLE modification_requests ADD COLUMN supplement_proof_key VARCHAR(512);
ALTER TABLE modification_requests ADD COLUMN supplement_proof_last_five VARCHAR(5);
ALTER TABLE modification_requests ADD COLUMN supplement_idempotency_key VARCHAR(100);
-- 補款憑證提交時間（同時為該次線下交易免責聲明的同意時間）
ALTER TABLE modification_requests ADD COLUMN supplement_submitted_at TIMESTAMPTZ;
-- 保母核對補款入帳時間；非 NULL 代表這筆補款已結清
ALTER TABLE modification_requests ADD COLUMN supplement_verified_at TIMESTAMPTZ;

CREATE UNIQUE INDEX idx_mod_req_supplement_idempotency
    ON modification_requests (supplement_idempotency_key)
    WHERE supplement_idempotency_key IS NOT NULL;
