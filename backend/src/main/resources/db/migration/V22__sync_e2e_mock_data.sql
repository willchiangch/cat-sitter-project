-- Task: Synchronize E2E mock data for cloud testing
-- Fixed: Removed non-existent fields (nickname, weight, breed) to match database schema

-- 1. SOPHIA (Sitter) Account
INSERT INTO accounts (id, email, password_hash, oauth_provider, status, created_at, updated_at)
VALUES ('efefefef-0000-0000-0000-000000000001', 'sophia@example.com', 'MOCK_HASH', 'GOOGLE', 'ACTIVE', NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- 2. JAMES (Client) Account
INSERT INTO accounts (id, email, password_hash, oauth_provider, status, created_at, updated_at)
VALUES ('efefefef-0000-0000-0000-000000000002', 'james@example.com', 'MOCK_HASH', 'GOOGLE', 'ACTIVE', NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- 3. Profiles for Sophia and James
-- Fixed: using 'name' instead of 'nickname'
INSERT INTO profiles (id, account_id, name, role_type, created_at, updated_at)
VALUES 
('efefefef-0000-0000-0000-000000000001', 'efefefef-0000-0000-0000-000000000001', 'Sophia Sitter', 'SITTER', NOW(), NOW()),
('efefefef-0000-0000-0000-000000000002', 'efefefef-0000-0000-0000-000000000002', 'James Wilson', 'CLIENT', NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- 4. Ensure James has pets 'Fluffy' and 'Oliver' for E2E tests
INSERT INTO pets (id, client_profile_id, name, species, gender, weight_kg, is_neutered, created_at, updated_at)
VALUES 
('efefefef-0000-0000-0000-000000000005', 'efefefef-0000-0000-0000-000000000002', 'Fluffy', 'CAT', 'FEMALE', 4.5, TRUE, NOW(), NOW()),
('efefefef-0000-0000-0000-000000000006', 'efefefef-0000-0000-0000-000000000002', 'Oliver', 'CAT', 'MALE', 5.0, TRUE, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- 5. Whitelist James for Sophia (Required for booking-lifecycle.spec.js)
INSERT INTO sitter_client_whitelists (id, sitter_profile_id, client_profile_id, skip_questionnaire, notes, created_at, updated_at)
VALUES ('efefefef-0000-0000-0000-000000000010', 'efefefef-0000-0000-0000-000000000001', 'efefefef-0000-0000-0000-000000000002', TRUE, 'E2E Test WhiteList', NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- 6. Mock Service for Sophia
INSERT INTO services (id, sitter_profile_id, name, base_price, duration_minutes, supported_pet_types, is_active, created_at, updated_at)
VALUES ('efefefef-0000-0000-0000-000000000020', 'efefefef-0000-0000-0000-000000000001', 'Standard Care', 500, 60, '["CAT"]', TRUE, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- 7. Mock Order for Oliver (James Wilson booking Sophia Sitter)
INSERT INTO orders (id, client_profile_id, current_sitter_id, service_id, base_amount, total_amount, order_status, payment_status, questionnaire_status, created_at, updated_at)
VALUES ('efefefef-0000-0000-0000-000000000030', 'efefefef-0000-0000-0000-000000000002', 'efefefef-0000-0000-0000-000000000001', 'efefefef-0000-0000-0000-000000000020', 500, 500, 'CONFIRMED', 'PAID', 'COMPLETED', NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- 8. Mock Visit for Today
INSERT INTO visits (id, order_id, visit_start_time, visit_end_time, status, created_at, updated_at)
VALUES ('efefefef-0000-0000-0000-000000000040', 'efefefef-0000-0000-0000-000000000030', NOW(), NOW() + interval '1 hour', 'UPCOMING', NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- 9. Link Oliver to the Visit (using 'visit_services' which replaced 'visit_tasks' in V21)
INSERT INTO visit_services (id, visit_id, pet_id, service_type, is_completed, created_at, updated_at)
VALUES ('efefefef-0000-0000-0000-000000000050', 'efefefef-0000-0000-0000-000000000040', 'efefefef-0000-0000-0000-000000000006', 'FEEDING', FALSE, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;
