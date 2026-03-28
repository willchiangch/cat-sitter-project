-- Add calendar_event_id to visits to store external event reference
ALTER TABLE visits ADD COLUMN calendar_event_id VARCHAR(255);

-- Create table for storing sitter calendar OAuth configurations
CREATE TABLE sitter_calendar_configs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sitter_profile_id UUID NOT NULL REFERENCES profiles(id),
    provider VARCHAR(50) NOT NULL, -- e.g., 'GOOGLE', 'ICLOUD'
    access_token TEXT NOT NULL,
    refresh_token TEXT,
    expires_at TIMESTAMPTZ NOT NULL,
    
    created_by VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by VARCHAR(255),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    
    CONSTRAINT uk_sitter_calendar_config_profile UNIQUE (sitter_profile_id)
);

CREATE INDEX idx_sitter_calendar_configs_profile_id ON sitter_calendar_configs(sitter_profile_id);
