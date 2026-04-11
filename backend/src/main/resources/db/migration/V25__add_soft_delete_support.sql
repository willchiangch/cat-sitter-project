-- ==========================================
-- V25: Add Soft Delete support for core entities
-- ==========================================

-- 1. Add deleted_at column to target tables
ALTER TABLE accounts ADD COLUMN deleted_at TIMESTAMPTZ;
ALTER TABLE profiles ADD COLUMN deleted_at TIMESTAMPTZ;
ALTER TABLE pets ADD COLUMN deleted_at TIMESTAMPTZ;
ALTER TABLE services ADD COLUMN deleted_at TIMESTAMPTZ;
ALTER TABLE orders ADD COLUMN deleted_at TIMESTAMPTZ;
ALTER TABLE visits ADD COLUMN deleted_at TIMESTAMPTZ;

-- 2. Update uniqueness constraints for accounts (email)
-- 移除舊的 UNIQUE 約束 (Postgres 預設名稱通常是 table_column_key)
ALTER TABLE accounts DROP CONSTRAINT accounts_email_key;
CREATE UNIQUE INDEX idx_accounts_email_active ON accounts(email) WHERE deleted_at IS NULL;

-- 3. Update uniqueness constraints for profiles (slug & name)
-- 處理 slug
DROP INDEX IF EXISTS idx_profiles_slug; -- 移除 V20 建立的舊索引
CREATE UNIQUE INDEX idx_profiles_slug_active ON profiles(slug) WHERE deleted_at IS NULL;

-- 處理 name (V1 中定義為 UNIQUE)
ALTER TABLE profiles DROP CONSTRAINT IF EXISTS profiles_name_key;
CREATE UNIQUE INDEX idx_profiles_name_active ON profiles(name) WHERE deleted_at IS NULL;

-- 4. 為所有 deleted_at 建立普通索引以優化查詢效能
CREATE INDEX idx_accounts_deleted_at ON accounts(deleted_at);
CREATE INDEX idx_profiles_deleted_at ON profiles(deleted_at);
CREATE INDEX idx_pets_deleted_at ON pets(deleted_at);
CREATE INDEX idx_services_deleted_at ON services(deleted_at);
CREATE INDEX idx_orders_deleted_at ON orders(deleted_at);
CREATE INDEX idx_visits_deleted_at ON visits(deleted_at);
