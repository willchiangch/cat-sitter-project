-- 1. 新增折扣類型到 promo_codes
ALTER TABLE promo_codes
    ADD COLUMN IF NOT EXISTS discount_type VARCHAR(20) NOT NULL DEFAULT 'FIXED',
    -- FIXED = 固定金額折扣, PERCENT = 百分比折扣 (0-100)
    ADD COLUMN IF NOT EXISTS discount_percent DECIMAL(5,2);

-- 2. 訂閱變更歷程 log
CREATE TABLE subscription_change_logs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sitter_profile_id UUID NOT NULL REFERENCES profiles(id),
    from_plan_code  VARCHAR(20),           -- NULL = 全新訂閱
    to_plan_code    VARCHAR(20) NOT NULL,
    change_type     VARCHAR(30) NOT NULL,  -- SUBSCRIBE / UPGRADE / DOWNGRADE / CANCEL / FREE_REDEMPTION
    promo_code_id   UUID REFERENCES promo_codes(id),
    promo_code_used VARCHAR(50),           -- 冗余存碼，防止 code 被刪
    original_amount DECIMAL(10,2) NOT NULL DEFAULT 0,
    discount_amount DECIMAL(10,2) NOT NULL DEFAULT 0,
    final_amount    DECIMAL(10,2) NOT NULL DEFAULT 0,
    note            TEXT,
    created_by      VARCHAR(255),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by      VARCHAR(255),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_sub_change_log_sitter ON subscription_change_logs(sitter_profile_id);
CREATE INDEX idx_sub_change_log_created ON subscription_change_logs(created_at DESC);
