-- 服務方案「下架/上架」應為可逆狀態切換，與「刪除」(is_deleted, 保留供未來稽核用途) 分離
ALTER TABLE service_plans ADD COLUMN is_active BOOLEAN NOT NULL DEFAULT TRUE;
