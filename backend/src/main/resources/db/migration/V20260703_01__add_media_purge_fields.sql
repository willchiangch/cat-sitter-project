-- V20260703_01__add_media_purge_fields.sql

-- 1. 於 service_report_media 新增物理清理標記與時間
ALTER TABLE service_report_media ADD COLUMN is_purged BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE service_report_media ADD COLUMN purged_at TIMESTAMPTZ;

-- 2. 於 orders 新增逾期提醒通知發送標記
ALTER TABLE orders ADD COLUMN media_expiry_warned BOOLEAN NOT NULL DEFAULT FALSE;

-- 3. 建立複合索引優化清理排程的掃描效率
CREATE INDEX idx_report_media_purge ON service_report_media(is_purged) WHERE is_purged = FALSE;
CREATE INDEX idx_orders_expiry_warn ON orders(media_expiry_warned, status) WHERE status = 'COMPLETED';
