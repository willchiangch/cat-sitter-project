-- V20260523_02: 插入本地聯調所需的 Sitter 與 Owner 種子資料
INSERT INTO users (id, email, password_hash, full_name, role, version, created_at, updated_at, is_deleted)
VALUES 
('3d498178-14c0-4376-b81e-7fb02e615dda', 'sitter@test.com', '$2a$06$qbmmryL1QxU1iDbLvrMxUOTkW.seufcKm./fJzij7Yp1n5N0krai6', '本地測試保母', 'SITTER', 1, NOW(), NOW(), false),
('1031efbc-583a-4062-9a35-15706a3384c6', 'owner@test.com', '$2a$06$qbmmryL1QxU1iDbLvrMxUOTkW.seufcKm./fJzij7Yp1n5N0krai6', '本地測試飼主', 'OWNER', 1, NOW(), NOW(), false)
ON CONFLICT (id) DO NOTHING;

-- 插入一個預設的訂閱方案，防止 SaaS Gating 因查無 Sitter 訂閱而阻攔
INSERT INTO subscriptions (id, sitter_id, plan_tier, monthly_order_count, expired_at, version, created_at, updated_at, is_deleted)
VALUES
(gen_random_uuid(), '3d498178-14c0-4376-b81e-7fb02e615dda', 'FREE', 999, NOW() + INTERVAL '1 year', 0, NOW(), NOW(), false)
ON CONFLICT (sitter_id) DO NOTHING;
