-- PRD-005: 飼主預約時回覆保母事前問卷 (PRD-004) 的快照
ALTER TABLE orders ADD COLUMN questionnaire_answers JSONB NOT NULL DEFAULT '[]';
