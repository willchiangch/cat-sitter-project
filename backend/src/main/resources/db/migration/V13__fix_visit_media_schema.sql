-- V13: Final sync for Questionnaire Editor & Visit Media

-- Ensure sitter_questions has the correct column names matching Entity @Column
ALTER TABLE sitter_questions ADD COLUMN IF NOT EXISTS question_type VARCHAR(50) DEFAULT 'TEXT';
ALTER TABLE sitter_questions ADD COLUMN IF NOT EXISTS is_required BOOLEAN DEFAULT FALSE;

-- Add missing option_order to sitter_question_options
ALTER TABLE sitter_question_options ADD COLUMN IF NOT EXISTS option_order INT DEFAULT 0;

-- Add missing caption to visit_media
ALTER TABLE visit_media ADD COLUMN IF NOT EXISTS caption VARCHAR(255);
