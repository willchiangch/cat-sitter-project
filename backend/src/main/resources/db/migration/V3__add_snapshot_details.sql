-- V3: 補齊 order_snapshots 財務細節欄位

ALTER TABLE order_snapshots 
ADD COLUMN snapshot_plan_title VARCHAR(255) NOT NULL DEFAULT '未命名方案',
ADD COLUMN snapshot_unit_price INT NOT NULL DEFAULT 0;

-- 移除 DEFAULT 約束，確保後續寫入必須顯式提供值 (清理暫時的 Default)
ALTER TABLE order_snapshots ALTER COLUMN snapshot_plan_title DROP DEFAULT;
ALTER TABLE order_snapshots ALTER COLUMN snapshot_unit_price DROP DEFAULT;
