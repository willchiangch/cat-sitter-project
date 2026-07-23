-- PRD-000 AC-6：生物辨識登入 (WebAuthn/FIDO2)
-- webauthn_credentials：已註冊的生物辨識憑證（一人可多筆，因為憑證本質綁裝置）
CREATE TABLE webauthn_credentials (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    credential_id TEXT NOT NULL UNIQUE,
    public_key_cose TEXT NOT NULL,
    sign_count BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_used_at TIMESTAMPTZ
);
CREATE INDEX idx_webauthn_credentials_user_id ON webauthn_credentials(user_id);

-- webauthn_challenges：註冊/登入挑戰的短效期暫存（後端無 session，需在 options 與 verify 兩次呼叫間存放）
CREATE TABLE webauthn_challenges (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    challenge_type VARCHAR(20) NOT NULL,  -- REGISTRATION / AUTHENTICATION，避免同一使用者同時有兩種挑戰時互相覆蓋讀錯
    request_json TEXT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_webauthn_challenges_user_id_type ON webauthn_challenges(user_id, challenge_type);
