-- PRD-016: 變更請求的新日期清單需伺服端持久化，
-- 避免確認變更時依賴前端重新提交（Zero-Trust：日期應以發起變更當下鎖定的內容為準）
ALTER TABLE modification_requests ADD COLUMN dates jsonb;
