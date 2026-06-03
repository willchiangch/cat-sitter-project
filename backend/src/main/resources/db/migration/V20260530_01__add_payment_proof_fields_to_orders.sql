-- V20260530_01: orders 與 profiles 擴充線下憑證與收款帳戶，並預填測試種子資料

-- 1. orders 表欄位擴充
ALTER TABLE orders ADD COLUMN payment_proof_url VARCHAR(512);
ALTER TABLE orders ADD COLUMN payment_proof_last_five VARCHAR(5);
ALTER TABLE orders ADD COLUMN disclaimer_agreed BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE orders ADD COLUMN disclaimer_agreed_at TIMESTAMPTZ;
ALTER TABLE orders ADD COLUMN payment_idempotency_key VARCHAR(100);

-- 新增 payment_idempotency_key 局部唯一索引，保證付款冪等性防護
CREATE UNIQUE INDEX idx_orders_payment_idempotency ON orders(payment_idempotency_key) WHERE payment_idempotency_key IS NOT NULL;

-- 2. profiles 表欄位擴充
ALTER TABLE profiles ADD COLUMN bank_account_info JSONB;

-- 3. 預填/更新本地測試使用者的 profiles 種子資料
-- 3.1 本地測試保母的 SITTER Profile，預填加密過的收款帳戶
INSERT INTO profiles (id, user_id, type, trust_score, kyc_status, bank_account_info, created_at, updated_at)
VALUES (
    gen_random_uuid(), 
    '3d498178-14c0-4376-b81e-7fb02e615dda', 
    'SITTER', 
    100, 
    'VERIFIED', 
    '{"ciphertext": "5+TXt2qnkqAnYDj7poTs++mxY6z9M30khBufeSv/hgkFJ7vRj0CsfZEIhx5x9g7VC3qodqN4oT24tvdVTcFJvKVA3PeZ3yIlm9LCp6DJbIJ0vNH6fgvSJJYotiYNUUPEzoJj9hOAxbYkZ1EPDxrNrMZJfv/sGK5H2Ejr+ZhtdStOuERyLXIQgYpvdFs="}'::jsonb, 
    NOW(), 
    NOW()
)
ON CONFLICT (user_id, type) 
DO UPDATE SET bank_account_info = EXCLUDED.bank_account_info, kyc_status = 'VERIFIED';

-- 3.2 本地測試飼主的 CLIENT Profile
INSERT INTO profiles (id, user_id, type, trust_score, kyc_status, bank_account_info, created_at, updated_at)
VALUES (
    gen_random_uuid(), 
    '1031efbc-583a-4062-9a35-15706a3384c6', 
    'CLIENT', 
    100, 
    'VERIFIED', 
    NULL, 
    NOW(), 
    NOW()
)
ON CONFLICT (user_id, type) 
DO NOTHING;
