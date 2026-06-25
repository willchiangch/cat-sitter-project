-- 1. 擴充 profiles 表
ALTER TABLE profiles ADD COLUMN avatar_url VARCHAR(512);
ALTER TABLE profiles ADD COLUMN display_name VARCHAR(100);
ALTER TABLE profiles ADD COLUMN bio TEXT;
ALTER TABLE profiles ADD COLUMN is_visible BOOLEAN NOT NULL DEFAULT TRUE;

-- 2. 新增 sitter_tags 表
CREATE TABLE sitter_tags (
    id UUID PRIMARY KEY,
    profile_id UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
    tag VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_sitter_tag UNIQUE (profile_id, tag)
);
CREATE INDEX idx_sitter_tags_tag ON sitter_tags(tag);

-- 3. 新增 sitter_service_areas 表
CREATE TABLE sitter_service_areas (
    id UUID PRIMARY KEY,
    profile_id UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
    city VARCHAR(50) NOT NULL,
    district VARCHAR(50) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_sitter_area UNIQUE (profile_id, city, district)
);
CREATE INDEX idx_sitter_areas_city_dist ON sitter_service_areas(city, district);

-- 4. 新增 forbidden_keywords 表
CREATE TABLE forbidden_keywords (
    id UUID PRIMARY KEY,
    keyword VARCHAR(50) NOT NULL UNIQUE,
    created_by UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
