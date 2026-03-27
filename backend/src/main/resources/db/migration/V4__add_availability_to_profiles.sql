-- ==========================================
-- V4: Add Availability Fields to Profiles
-- ==========================================

ALTER TABLE profiles
    ADD COLUMN weekly_availability JSONB,
    ADD COLUMN specific_exclusions JSONB,
    ADD COLUMN slug VARCHAR(255) UNIQUE;

CREATE INDEX idx_profiles_slug ON profiles(slug);

COMMENT ON COLUMN profiles.weekly_availability IS '每週固定可用時段，例如 {"MONDAY": ["09:00-12:00"], "TUESDAY": []}';
COMMENT ON COLUMN profiles.specific_exclusions IS '特定排除日期，例如 ["2024-04-04", "2024-04-05"]';
