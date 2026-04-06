CREATE TABLE IF NOT EXISTS verification_codes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    code VARCHAR(10) NOT NULL,
    expiry_at TIMESTAMP NOT NULL,
    used_at TIMESTAMP
);
