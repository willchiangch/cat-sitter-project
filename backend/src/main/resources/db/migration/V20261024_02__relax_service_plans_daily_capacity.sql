-- 移除「每日容量 dailyCapacity」功能（2026-10 backlog 第 7 批）：系統不做接單容量控管，
-- 要不要接單由保母自行判斷（PRD-005 同日已有訂單不阻擋、PRD-006 保母最終決定權）。
--
-- 新版 entity 已不再映射 daily_capacity。這次只放寬約束、不 DROP COLUMN：
-- Cloud Run 部署時新 revision 啟動跑 Flyway，舊 revision 仍在服務，舊 entity 仍會讀寫這個欄位。
-- 保留 DEFAULT 1：新 revision INSERT 不帶此欄時仍寫入 1，舊 revision 讀到的永遠不是 NULL
-- （舊程式 `bookedCount >= plan.getDailyCapacity()` 遇 NULL 會 unboxing NPE）。
-- 下一次部署（確認已無舊 revision）後再另寫 migration：
--   ALTER TABLE service_plans DROP COLUMN IF EXISTS daily_capacity;
ALTER TABLE service_plans ALTER COLUMN daily_capacity DROP NOT NULL;
