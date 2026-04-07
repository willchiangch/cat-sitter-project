-- ==========================================
-- V20: Backfill missing slugs for Sitter Profiles
-- ==========================================

UPDATE profiles
SET slug = lower(substring(md5(random()::text), 1, 8))
WHERE role_type = 'SITTER' 
  AND (slug IS NULL OR slug = '');

-- 確保索引完整性（如果之前因為 NULL 沒能生效）
CREATE INDEX IF NOT EXISTS idx_profiles_slug ON profiles(slug);
