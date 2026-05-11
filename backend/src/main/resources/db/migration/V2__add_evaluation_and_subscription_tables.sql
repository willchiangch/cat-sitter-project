-- V2: 新增訂閱方案表，並升級服務方案欄位 (移除重複的 order_snapshots)

-- 1. 升級 service_plans 補齊媒體規則 (PRD-013)
ALTER TABLE service_plans 
ADD COLUMN media_retention_days INT NOT NULL DEFAULT 30,
ADD COLUMN max_photos INT NOT NULL DEFAULT 10,
ADD COLUMN max_video_seconds INT NOT NULL DEFAULT 0;

-- 2. 建立 subscriptions 表 (SD-ERD 2.3)
CREATE TABLE subscriptions (
    id UUID PRIMARY KEY,
    version INT NOT NULL DEFAULT 0,
    sitter_id UUID NOT NULL REFERENCES users(id),
    plan_tier VARCHAR(50) NOT NULL, -- FREE, BASIC, PRO, ULTIMATE
    monthly_order_count INT NOT NULL DEFAULT 0,
    expired_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by UUID,
    updated_by UUID,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT uk_subscription_sitter UNIQUE (sitter_id)
);

-- 建立索引
CREATE INDEX idx_subscriptions_sitter ON subscriptions(sitter_id);
