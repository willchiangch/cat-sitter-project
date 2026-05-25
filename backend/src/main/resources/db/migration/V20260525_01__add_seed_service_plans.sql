-- 為種子保母插入 E2E 測試所需的服務方案
-- 使用固定 UUID 確保冪等性（ON CONFLICT DO NOTHING）
INSERT INTO service_plans (id, sitter_id, name, daily_capacity, price, sort_order, default_tasks, applicable_pet_types, description, is_restricted, is_deleted, version, created_at, updated_at)
VALUES
  ('11111111-1111-1111-1111-111111111111', '3d498178-14c0-4376-b81e-7fb02e615dda', '基礎照顧', 1, 500, 0, '[]', '["CAT"]', '基本日常照顧，包含餵食與清潔', false, false, 1, NOW(), NOW()),
  ('22222222-2222-2222-2222-222222222222', '3d498178-14c0-4376-b81e-7fb02e615dda', '進階陪伴', 1, 800, 1, '[]', '["CAT"]', '全程陪伴互動，適合需要大量關注的貓咪', false, false, 1, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;
