-- V20260526_01: 建立毛孩資料表與注意事項編輯日誌表
-- Standards: SD-GLOBAL-SPEC, SD-ERD

-- 1. 建立毛孩基礎資料表
CREATE TABLE pets (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id UUID NOT NULL REFERENCES users(id),
    name VARCHAR(100) NOT NULL,
    species VARCHAR(50) NOT NULL, -- e.g. CAT, DOG, BIRD, etc.
    gender VARCHAR(20),           -- e.g. MALE, FEMALE
    neutered BOOLEAN,
    weight DECIMAL(5,2),          -- 體重數字，兩位小數
    birth_year INT,
    photo_url VARCHAR(512),
    medical_personality_notes TEXT, -- 醫療/個性備註
    environmental_notes TEXT,       -- 環境備註
    
    -- 標準欄位
    version INT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by UUID REFERENCES users(id),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by UUID REFERENCES users(id),
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE
);

-- 2. 建立毛孩注意事項編輯日誌表
CREATE TABLE pet_edit_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    pet_id UUID NOT NULL REFERENCES pets(id) ON DELETE CASCADE,
    editor_id UUID NOT NULL REFERENCES users(id),
    diff_summary JSONB NOT NULL,    -- 存放變更前後的注意事項快照與角色 (editorRole)
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 3. 建立索引優化性能
CREATE INDEX idx_pets_owner ON pets(owner_id) WHERE is_deleted = FALSE;
CREATE INDEX idx_pet_edit_logs_pet ON pet_edit_logs(pet_id);
