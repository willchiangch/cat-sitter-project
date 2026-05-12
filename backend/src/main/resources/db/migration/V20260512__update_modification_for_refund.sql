-- V20260512__update_modification_for_refund.sql
-- 僅在協商表新增退款憑證欄位
ALTER TABLE modification_requests ADD COLUMN refund_proof_url VARCHAR(500);
