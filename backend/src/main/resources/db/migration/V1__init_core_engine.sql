-- V1: Core Engine Schema Initialization
-- Standards: SD-GLOBAL-SPEC, SD-ERD

-- Enable UUID support
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- 1. 基礎使用者與角色表 (簡化版，供核心引擎引用)
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(255) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    full_name VARCHAR(100),
    role VARCHAR(20) NOT NULL, -- OWNER, SITTER, ADMIN
    
    version INT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by UUID,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by UUID,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE
);

-- 2. 訂單主表 (ORDERS)
CREATE TABLE orders (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id UUID NOT NULL REFERENCES users(id),
    sitter_id UUID NOT NULL REFERENCES users(id),
    items JSONB NOT NULL, -- 紀錄原始預約內容 (方案組合、毛孩資料)
    
    status VARCHAR(30) NOT NULL, -- PENDING, PENDING_PAYMENT, CONFIRMED, IN_PROGRESS, COMPLETED, CANCELLED, DISPUTED, REFUND_VERIFY
    
    -- 財務相關 (SD-GLOBAL-SPEC: 使用 INT 儲存)
    total_amount INT NOT NULL DEFAULT 0,
    adjustment_amount INT NOT NULL DEFAULT 0,
    adjustment_reason TEXT,
    
    -- 時間軸 (SD-ERD 2.2)
    paid_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    payout_at TIMESTAMPTZ,
    
    -- 狀態標記
    waiting_for_owner_action BOOLEAN NOT NULL DEFAULT FALSE,
    is_disputed BOOLEAN NOT NULL DEFAULT FALSE,
    
    -- 併發與防重複 (SD-005)
    idempotency_key VARCHAR(100) UNIQUE,
    version INT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by UUID,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by UUID,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE
);

-- 3. 行程表 (VISITS)
CREATE TABLE visits (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id UUID NOT NULL REFERENCES orders(id),
    status VARCHAR(20) NOT NULL, -- PENDING, DONE, CLOSED_BY_SYSTEM
    scheduled_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    
    version INT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by UUID,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by UUID,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE
);

-- 4. 訂單快照 (ORDER_SNAPSHOTS - SD-016 關鍵)
CREATE TABLE order_snapshots (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id UUID NOT NULL REFERENCES orders(id),
    
    -- 財務快照
    snapshot_original_total INT NOT NULL,
    adjustment_amount INT NOT NULL,
    
    -- 媒體保留規則快照 (PRD-013)
    media_retention_days INT NOT NULL,
    max_photos INT NOT NULL,
    max_video_seconds INT NOT NULL,
    
    -- 完整內容快照 (JSONB)
    snapshot_data JSONB NOT NULL,
    
    version INT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by UUID,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by UUID,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT uk_order_snapshot_order_id UNIQUE (order_id)
);

-- 5. 業務日誌 (ORDER_LOGS - NFR-006)
CREATE TABLE order_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id UUID NOT NULL REFERENCES orders(id),
    operator_id VARCHAR(50), -- 可存入 USER_ID 或 'SYSTEM_CRON'
    action_type VARCHAR(50) NOT NULL, -- ORDER_CREATED, QUOTE_SENT, AUTO_COMPLETED, etc.
    
    -- 紀錄變更前後值
    payload JSONB, -- 存放 {before: {...}, after: {...}, meta: {ip, ua}}
    
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 6. 變更協商表 (MODIFICATION_REQUESTS - SD-016)
CREATE TABLE modification_requests (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING_REVIEW',
    diff_amount INT,
    requested_by VARCHAR(20) NOT NULL, -- OWNER, SITTER
    payload JSONB, -- 存放變更後的 items 快照
    
    version INT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by UUID,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by UUID,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE
);

-- 同一筆訂單同一時間僅能有一筆待審查的變更請求 (Partial Index)
CREATE UNIQUE INDEX uk_active_modification ON modification_requests(order_id) WHERE status = 'PENDING_REVIEW';

-- 索引優化 (NFR-001 Performance)
CREATE INDEX idx_orders_owner ON orders(owner_id);
CREATE INDEX idx_orders_sitter ON orders(sitter_id);
CREATE INDEX idx_orders_status ON orders(status);
CREATE INDEX idx_visits_order ON visits(order_id);
CREATE INDEX idx_visits_status_scheduled ON visits(status, scheduled_at);
