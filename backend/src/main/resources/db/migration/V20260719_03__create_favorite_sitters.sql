-- PRD-019: 飼主我的最愛保母
CREATE TABLE favorite_sitters (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id UUID NOT NULL REFERENCES users(id),
    sitter_id UUID NOT NULL REFERENCES users(id),

    version INT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by UUID,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by UUID,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,

    CONSTRAINT uk_favorite_owner_sitter UNIQUE (owner_id, sitter_id)
);

CREATE INDEX idx_favorite_sitters_owner_id ON favorite_sitters(owner_id);
