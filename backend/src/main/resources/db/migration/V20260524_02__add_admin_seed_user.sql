-- V20260524_02: 插入本地聯調與 E2E 測試所需的 Admin 種子資料
INSERT INTO users (id, email, password_hash, full_name, role, version, created_at, updated_at, is_deleted)
VALUES 
('d5f7f329-87a4-4df1-8db6-444f2ef3132e', 'admin@test.com', '$2a$06$qbmmryL1QxU1iDbLvrMxUOTkW.seufcKm./fJzij7Yp1n5N0krai6', '本地測試管理員', 'ADMIN', 1, NOW(), NOW(), false)
ON CONFLICT (id) DO NOTHING;
