-- PRD-006 例外處理「飼主在保母審核中嘗試取消」（fix-batch10 H5）：記錄訂單由誰取消，
-- 保母端據此顯示「飼主已取消預約」（保母婉拒 / 訂單變更取消同為 CANCELLED，需要區分）。
-- 只新增可為 NULL 的欄位：Cloud Run 部署時舊 revision 的 entity 不映射此欄，讀寫都不受影響。
-- 既有 CANCELLED 訂單維持 NULL（無法回溯取消者）。
ALTER TABLE orders ADD COLUMN cancelled_by VARCHAR(16);
