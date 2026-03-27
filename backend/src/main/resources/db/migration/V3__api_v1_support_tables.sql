-- ============================================================
-- V3: API V1 support tables
-- Purpose:
-- 1) Booking payment proof upload
-- 2) Visit checklist/media execution log
-- 3) Subscription limit and webhook idempotency
-- 4) Notification center
-- ============================================================

CREATE TABLE payment_proofs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id UUID NOT NULL REFERENCES orders(id),
    proof_url VARCHAR(1024) NOT NULL,
    note VARCHAR(500),
    uploaded_by_profile_id UUID NOT NULL REFERENCES profiles(id),
    reviewed_by_profile_id UUID REFERENCES profiles(id),
    reviewed_at TIMESTAMPTZ,

    created_by VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by VARCHAR(255),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE visit_checklist_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    visit_id UUID NOT NULL REFERENCES visits(id),
    item_name VARCHAR(255) NOT NULL,
    is_custom BOOLEAN NOT NULL DEFAULT FALSE,
    is_completed BOOLEAN NOT NULL DEFAULT FALSE,
    sort_order INT NOT NULL DEFAULT 0,
    completed_at TIMESTAMPTZ,

    created_by VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by VARCHAR(255),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE visit_media_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    visit_id UUID NOT NULL REFERENCES visits(id),
    media_type VARCHAR(20) NOT NULL, -- PHOTO / VIDEO
    media_url VARCHAR(1024) NOT NULL,
    caption VARCHAR(500),

    created_by VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by VARCHAR(255),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE subscriptions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sitter_profile_id UUID NOT NULL REFERENCES profiles(id),
    plan_code VARCHAR(50) NOT NULL,
    status VARCHAR(50) NOT NULL,
    monthly_order_limit INT,
    started_at TIMESTAMPTZ,
    expired_at TIMESTAMPTZ,

    created_by VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by VARCHAR(255),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE payment_webhook_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider VARCHAR(50) NOT NULL, -- ECPAY / TAPPAY / LINEPAY
    event_id VARCHAR(255) NOT NULL,
    event_type VARCHAR(100),
    payload JSONB NOT NULL,
    processed BOOLEAN NOT NULL DEFAULT FALSE,
    processed_at TIMESTAMPTZ,
    error_message TEXT,

    created_by VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by VARCHAR(255),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_payment_webhook_events_provider_event UNIQUE (provider, event_id)
);

CREATE TABLE notifications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    profile_id UUID NOT NULL REFERENCES profiles(id),
    type VARCHAR(100) NOT NULL,
    title VARCHAR(255) NOT NULL,
    body TEXT,
    metadata JSONB,
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    read_at TIMESTAMPTZ,

    created_by VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by VARCHAR(255),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_payment_proofs_order_id ON payment_proofs(order_id);
CREATE INDEX idx_payment_proofs_uploaded_by ON payment_proofs(uploaded_by_profile_id);

CREATE INDEX idx_visit_checklist_items_visit_id ON visit_checklist_items(visit_id);
CREATE INDEX idx_visit_checklist_items_completed ON visit_checklist_items(visit_id, is_completed);

CREATE INDEX idx_visit_media_logs_visit_id ON visit_media_logs(visit_id);
CREATE INDEX idx_subscriptions_sitter_profile_id ON subscriptions(sitter_profile_id);
CREATE INDEX idx_subscriptions_status ON subscriptions(status);

CREATE INDEX idx_webhook_events_provider_event ON payment_webhook_events(provider, event_id);
CREATE INDEX idx_notifications_profile_id_created_at ON notifications(profile_id, created_at DESC);
CREATE INDEX idx_notifications_profile_unread ON notifications(profile_id, is_read);
