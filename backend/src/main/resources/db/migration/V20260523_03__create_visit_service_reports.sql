-- V20260523_03__create_visit_service_reports.sql

-- 1. 補齊 order_snapshots 的方案快照欄位
ALTER TABLE order_snapshots ADD COLUMN IF NOT EXISTS max_videos INT NOT NULL DEFAULT 0;
ALTER TABLE order_snapshots ADD COLUMN IF NOT EXISTS plan_tier VARCHAR(20) NOT NULL DEFAULT 'FREE';

-- 2. 服務日誌主表
CREATE TABLE visit_service_reports (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    visit_id      UUID NOT NULL UNIQUE REFERENCES visits(id) ON DELETE CASCADE,
    status        VARCHAR(20) NOT NULL DEFAULT 'DRAFT', -- DRAFT, SUBMITTED
    content       TEXT, -- 文字日誌 (最多 1000 字)
    submitted_at  TIMESTAMPTZ NULL, -- 送出時間 (可為 null，僅狀態為 SUBMITTED 時寫入)
    
    -- 審計與併發鎖定軌跡
    version       INT NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by    UUID,
    updated_by    UUID,
    is_deleted    BOOLEAN NOT NULL DEFAULT FALSE
);

-- 3. 服務日誌多媒體表
CREATE TABLE service_report_media (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    report_id     UUID NOT NULL REFERENCES visit_service_reports(id) ON DELETE CASCADE,
    media_url     VARCHAR(255) NOT NULL,
    media_type    VARCHAR(10) NOT NULL, -- IMAGE, VIDEO
    caption       VARCHAR(100), -- 說明文字 (最長 100 字，選填)
    
    -- 審計與併發鎖定軌跡
    version       INT NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by    UUID,
    updated_by    UUID,
    is_deleted    BOOLEAN NOT NULL DEFAULT FALSE
);

-- 4. 索引優化
CREATE INDEX idx_visit_service_reports_visit ON visit_service_reports(visit_id, is_deleted);
CREATE INDEX idx_service_report_media_report ON service_report_media(report_id, is_deleted);
