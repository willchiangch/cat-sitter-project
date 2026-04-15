-- 為 services 表新增方案內容描述欄位
ALTER TABLE services ADD COLUMN IF NOT EXISTS description TEXT;
