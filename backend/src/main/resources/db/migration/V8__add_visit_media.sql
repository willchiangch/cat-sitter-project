-- Create table for storing media attachments (photos/videos) for visits
CREATE TABLE visit_media (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    visit_id UUID NOT NULL REFERENCES visits(id) ON DELETE CASCADE,
    media_url VARCHAR(1024) NOT NULL,
    media_type VARCHAR(50) NOT NULL, -- e.g., 'PHOTO', 'VIDEO'
    file_size BIGINT,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at TIMESTAMPTZ,
    
    created_by VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by VARCHAR(255),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_visit_media_visit_id ON visit_media(visit_id);
CREATE INDEX idx_visit_media_created_at ON visit_media(created_at);
