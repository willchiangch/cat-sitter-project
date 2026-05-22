-- V20260522_01__create_care_notes_and_media.sql

-- 1. 照護記事本主表
CREATE TABLE care_notes (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sitter_id   UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    owner_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    version     INT NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by  UUID,
    updated_by  UUID,
    CONSTRAINT uq_care_note_sitter_owner UNIQUE (sitter_id, owner_id)
);

-- 2. 照護記事本條目明細表 (Value Object，物理刪除無 version/is_deleted)
CREATE TABLE care_note_items (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    care_note_id UUID NOT NULL REFERENCES care_notes(id) ON DELETE CASCADE,
    section_type VARCHAR(50) NOT NULL CHECK (section_type IN ('SERVICE', 'CONTACT', 'WARNING', 'PREFERENCE', 'HOSPITAL', 'OTHER')), 
    title        VARCHAR(255) NOT NULL,
    content      TEXT NOT NULL,
    sort_order   INT NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by   UUID
);

-- 3. 記事模板主表
CREATE TABLE care_note_templates (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sitter_id   UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name        VARCHAR(100) NOT NULL,
    version     INT NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by  UUID,
    updated_by  UUID
);

-- 4. 記事模板條目明細表 (Value Object，物理刪除無 version/is_deleted)
CREATE TABLE care_note_template_items (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    template_id  UUID NOT NULL REFERENCES care_note_templates(id) ON DELETE CASCADE,
    section_type VARCHAR(50) NOT NULL CHECK (section_type IN ('SERVICE', 'CONTACT', 'WARNING', 'PREFERENCE', 'HOSPITAL', 'OTHER')),
    title        VARCHAR(255) NOT NULL,
    content      TEXT NOT NULL,
    sort_order   INT NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by   UUID
);

-- 5. 照護媒體庫表 (只有新增/刪除，移除 version 與 updated_at 死欄位)
CREATE TABLE care_media (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sitter_id   UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    owner_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    caption     VARCHAR(255) NOT NULL,
    media_url   VARCHAR(1024) NOT NULL,
    media_type  VARCHAR(50) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by  UUID
);

-- 6. DB 冪等性防護表
CREATE TABLE idempotency_keys (
    idempotency_key VARCHAR(128) NOT NULL,
    user_id         UUID NOT NULL,
    response_body   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_idempotency PRIMARY KEY (idempotency_key, user_id)
);

-- 7. 索引
CREATE INDEX idx_care_notes_lookup ON care_notes(sitter_id, owner_id);
CREATE INDEX idx_care_note_items_ref ON care_note_items(care_note_id);
CREATE INDEX idx_care_note_templates_sitter ON care_note_templates(sitter_id);
CREATE INDEX idx_care_note_template_items_ref ON care_note_template_items(template_id);
CREATE INDEX idx_care_media_lookup ON care_media(sitter_id, owner_id);
CREATE INDEX idx_idempotency_keys_created ON idempotency_keys(created_at);
