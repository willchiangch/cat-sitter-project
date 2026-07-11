-- PRD-003 AC-1 要求方案需含時長，補上結構化欄位（先前遺漏，僅靠方案名稱文字表達）
ALTER TABLE service_plans ADD COLUMN duration_minutes INTEGER NOT NULL DEFAULT 60;
