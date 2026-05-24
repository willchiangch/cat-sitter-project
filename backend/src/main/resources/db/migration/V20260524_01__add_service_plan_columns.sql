-- 新增保母服務方案欄位
ALTER TABLE service_plans ADD COLUMN default_tasks JSONB NOT NULL DEFAULT '[]';
ALTER TABLE service_plans ADD COLUMN applicable_pet_types JSONB NOT NULL DEFAULT '[]';
ALTER TABLE service_plans ADD COLUMN description TEXT;
ALTER TABLE service_plans ADD COLUMN start_date DATE;
ALTER TABLE service_plans ADD COLUMN end_date DATE;
ALTER TABLE service_plans ADD COLUMN is_restricted BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE service_plans ADD COLUMN sort_order INT NOT NULL DEFAULT 0;

-- 建立索引以提升前台查詢性能
CREATE INDEX idx_service_plans_sitter_deleted ON service_plans(sitter_id) WHERE is_deleted = FALSE;
