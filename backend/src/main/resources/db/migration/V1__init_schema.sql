-- 啟用 UUID 擴充功能 (現代 PostgreSQL 預設支援，防呆加上)
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ==========================================
-- 領域一：帳號與身分核心 (Identity & Access)
-- ==========================================

CREATE TABLE accounts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255),
    oauth_provider VARCHAR(50) NOT NULL,
    oauth_id VARCHAR(255),
    status VARCHAR(50) NOT NULL,
    
    created_by VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by VARCHAR(255),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE profiles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id UUID NOT NULL REFERENCES accounts(id),
    role_type VARCHAR(50) NOT NULL,
    name VARCHAR(255) NOT NULL,
    avatar_url VARCHAR(1024),
    phone VARCHAR(50),
    address VARCHAR(255),
    service_areas JSONB,
    bio_summary TEXT,
    refusal_criteria TEXT,
    
    created_by VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by VARCHAR(255),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ==========================================
-- 領域二：業務設定與毛孩檔案 (Business & Subjects)
-- ==========================================

CREATE TABLE services (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sitter_profile_id UUID NOT NULL REFERENCES profiles(id),
    name VARCHAR(255) NOT NULL,
    base_price DECIMAL(10,2) NOT NULL,
    duration_minutes INT NOT NULL,
    supported_pet_types JSONB,
    bookable_start_date DATE,
    bookable_end_date DATE,
    advance_booking_days INT DEFAULT 0,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    
    created_by VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by VARCHAR(255),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE sitter_questions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sitter_profile_id UUID NOT NULL REFERENCES profiles(id),
    target_pet_type VARCHAR(50) NOT NULL,
    question_text VARCHAR(1024) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    
    created_by VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by VARCHAR(255),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE pets (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    client_profile_id UUID NOT NULL REFERENCES profiles(id),
    name VARCHAR(255) NOT NULL,
    species VARCHAR(50) NOT NULL,
    medical_notes TEXT,
    dietary_notes TEXT,
    personality_notes TEXT,
    other_notes TEXT,
    
    created_by VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by VARCHAR(255),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ==========================================
-- 領域三：社交與信任圈 (Social & Trust Network)
-- ==========================================

CREATE TABLE client_favorite_sitters (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    client_profile_id UUID NOT NULL REFERENCES profiles(id),
    sitter_profile_id UUID NOT NULL REFERENCES profiles(id),
    is_favorite BOOLEAN NOT NULL DEFAULT TRUE,
    
    created_by VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by VARCHAR(255),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE sitter_trust_circles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_sitter_id UUID NOT NULL REFERENCES profiles(id),
    trusted_sitter_id UUID NOT NULL REFERENCES profiles(id),
    status VARCHAR(50) NOT NULL,
    
    created_by VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by VARCHAR(255),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ==========================================
-- 領域四：交易、排程與執行 (Orders & Execution)
-- ==========================================

CREATE TABLE orders (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    client_profile_id UUID NOT NULL REFERENCES profiles(id),
    current_sitter_id UUID NOT NULL REFERENCES profiles(id),
    service_id UUID NOT NULL REFERENCES services(id),
    base_amount DECIMAL(10,2) NOT NULL,
    surcharge_amount DECIMAL(10,2) DEFAULT 0,
    discount_amount DECIMAL(10,2) DEFAULT 0,
    total_amount DECIMAL(10,2) NOT NULL,
    pricing_notes TEXT,
    order_status VARCHAR(50) NOT NULL,
    payment_status VARCHAR(50) NOT NULL,
    questionnaire_status VARCHAR(50) NOT NULL,
    
    created_by VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by VARCHAR(255),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE order_answers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id UUID NOT NULL REFERENCES orders(id),
    question_id UUID NOT NULL REFERENCES sitter_questions(id),
    answer_text TEXT NOT NULL,
    
    created_by VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by VARCHAR(255),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE order_transfers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id UUID NOT NULL REFERENCES orders(id),
    from_sitter_id UUID NOT NULL REFERENCES profiles(id),
    to_sitter_id UUID NOT NULL REFERENCES profiles(id),
    transfer_status VARCHAR(50) NOT NULL,
    
    created_by VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by VARCHAR(255),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE visits (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id UUID NOT NULL REFERENCES orders(id),
    visit_start_time TIMESTAMPTZ NOT NULL,
    visit_end_time TIMESTAMPTZ NOT NULL,
    status VARCHAR(50) NOT NULL,
    sitter_notes TEXT,
    
    created_by VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by VARCHAR(255),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE visit_tasks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    visit_id UUID NOT NULL REFERENCES visits(id),
    pet_id UUID REFERENCES pets(id), -- 可為空 (如環境清潔)
    task_type VARCHAR(50) NOT NULL,
    description VARCHAR(255),
    is_completed BOOLEAN NOT NULL DEFAULT FALSE,
    photo_url VARCHAR(1024),
    completed_at TIMESTAMPTZ,
    
    created_by VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by VARCHAR(255),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE order_action_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id UUID NOT NULL REFERENCES orders(id),
    visit_id UUID REFERENCES visits(id), -- 可為空
    actor_profile_id UUID REFERENCES profiles(id), -- 系統觸發時可為空
    action_type VARCHAR(50) NOT NULL,
    previous_status VARCHAR(50),
    new_status VARCHAR(50) NOT NULL,
    
    created_by VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by VARCHAR(255),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
