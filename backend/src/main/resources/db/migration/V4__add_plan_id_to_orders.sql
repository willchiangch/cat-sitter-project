-- V4: 在 orders 表中加入 plan_id 欄位以支援容量檢查
-- 為了開發方便與測試一致性，暫時不設 NOT NULL 以相容舊資料遷移，
-- 但 Entity 層級會要求必填。
ALTER TABLE orders 
ADD COLUMN plan_id UUID;
