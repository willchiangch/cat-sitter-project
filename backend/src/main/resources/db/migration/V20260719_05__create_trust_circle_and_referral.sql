-- PRD-010: 信任圈與轉介機制
CREATE TABLE trust_relationships (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    requester_id UUID NOT NULL REFERENCES users(id),
    target_id UUID NOT NULL REFERENCES users(id),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING', -- PENDING, ACCEPTED

    version INT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by UUID,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by UUID,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,

    CONSTRAINT uk_trust_requester_target UNIQUE (requester_id, target_id)
);

CREATE INDEX idx_trust_relationships_requester ON trust_relationships(requester_id);
CREATE INDEX idx_trust_relationships_target ON trust_relationships(target_id);

CREATE TABLE referral_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    referring_sitter_id UUID NOT NULL REFERENCES users(id),
    order_id UUID REFERENCES orders(id),
    owner_id UUID NOT NULL REFERENCES users(id),
    recommended_sitter_id UUID NOT NULL REFERENCES users(id),
    message TEXT,

    version INT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by UUID,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by UUID,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_referral_logs_owner ON referral_logs(owner_id);
CREATE INDEX idx_referral_logs_recommended_sitter ON referral_logs(recommended_sitter_id);
