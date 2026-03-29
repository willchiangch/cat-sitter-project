-- V10: Sync all recently added entities and fields with the database

-- 1. Profiles (Update for FINANCE/TRUST_CIRCLE/VERIFICATION features)
ALTER TABLE profiles 
ADD COLUMN IF NOT EXISTS is_verified BOOLEAN DEFAULT FALSE,
ADD COLUMN IF NOT EXISTS verified_at TIMESTAMPTZ,
ADD COLUMN IF NOT EXISTS id_card_front_url VARCHAR(1024),
ADD COLUMN IF NOT EXISTS id_card_back_url VARCHAR(1024),
ADD COLUMN IF NOT EXISTS professional_labels JSONB,
ADD COLUMN IF NOT EXISTS bank_code VARCHAR(10),
ADD COLUMN IF NOT EXISTS bank_account VARCHAR(50),
ADD COLUMN IF NOT EXISTS bank_account_holder VARCHAR(100);

-- 2. Sitter Questions (Update for QUESTIONNAIRE_EDITOR features)
ALTER TABLE sitter_questions
ADD COLUMN IF NOT EXISTS type VARCHAR(50) DEFAULT 'TEXT',
ADD COLUMN IF NOT EXISTS required BOOLEAN DEFAULT FALSE;

-- 2.1 Question Options Table
CREATE TABLE IF NOT EXISTS sitter_question_options (
    question_id UUID NOT NULL REFERENCES sitter_questions(id) ON DELETE CASCADE,
    option_text VARCHAR(255) NOT NULL
);

-- 3. Whitelist & Trust Circle Tables
CREATE TABLE IF NOT EXISTS sitter_client_whitelists (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sitter_profile_id UUID NOT NULL REFERENCES profiles(id),
    client_profile_id UUID NOT NULL REFERENCES profiles(id),
    skip_questionnaire BOOLEAN NOT NULL DEFAULT FALSE,
    notes TEXT,
    
    created_by VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by VARCHAR(255),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    
    UNIQUE(sitter_profile_id, client_profile_id)
);

CREATE TABLE IF NOT EXISTS sitter_trust_circles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_sitter_id UUID NOT NULL REFERENCES profiles(id),
    trusted_sitter_id UUID NOT NULL REFERENCES profiles(id),
    status VARCHAR(50) NOT NULL, -- PENDING, ACCEPTED, REJECTED
    
    created_by VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by VARCHAR(255),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    
    UNIQUE(owner_sitter_id, trusted_sitter_id)
);
