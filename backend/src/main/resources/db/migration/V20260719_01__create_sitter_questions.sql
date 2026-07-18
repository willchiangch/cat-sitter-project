-- PRD-004: 事前問卷設定
CREATE TABLE sitter_questions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sitter_id UUID NOT NULL REFERENCES users(id),
    question_text VARCHAR(200) NOT NULL,
    answer_type VARCHAR(20) NOT NULL, -- RADIO, CHECKBOX, INPUT, TEXTAREA
    options JSONB NOT NULL DEFAULT '[]',
    required BOOLEAN NOT NULL DEFAULT FALSE,
    sort_order INT NOT NULL DEFAULT 0,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,

    version INT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by UUID,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by UUID,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_sitter_questions_sitter_id ON sitter_questions(sitter_id);
