-- V23: Add Oliver (pet), Sophia's service, and a mock visit for dynamic dashboard testing
-- This ensures Sophia has real visit data instead of hardcoded mock values

-- 1. Add Oliver for James Wilson
INSERT INTO pets (id, client_profile_id, name, species, gender, weight_kg, is_neutered, created_at, updated_at)
VALUES ('efefefef-0000-0000-0000-000000000006', 'efefefef-0000-0000-0000-000000000002', 'Oliver', 'CAT', 'MALE', 5.0, TRUE, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- 2. Mock Service for Sophia
INSERT INTO services (id, sitter_profile_id, name, base_price, duration_minutes, supported_pet_types, is_active, created_at, updated_at)
VALUES ('efefefef-0000-0000-0000-000000000020', 'efefefef-0000-0000-0000-000000000001', 'Standard Care', 500, 60, '["CAT"]', TRUE, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- 3. Mock Order for Oliver (James Wilson booking Sophia Sitter)
INSERT INTO orders (id, client_profile_id, current_sitter_id, service_id, base_amount, total_amount, order_status, payment_status, questionnaire_status, created_at, updated_at)
VALUES ('efefefef-0000-0000-0000-000000000030', 'efefefef-0000-0000-0000-000000000002', 'efefefef-0000-0000-0000-000000000001', 'efefefef-0000-0000-0000-000000000020', 500, 500, 'CONFIRMED', 'PAID', 'COMPLETED', NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- 4. Mock Visit for Today
INSERT INTO visits (id, order_id, visit_start_time, visit_end_time, status, created_at, updated_at)
VALUES ('efefefef-0000-0000-0000-000000000040', 'efefefef-0000-0000-0000-000000000030', NOW(), NOW() + interval '1 hour', 'UPCOMING', NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- 5. Link Oliver to the Visit (using 'visit_services' which replaced 'visit_tasks' in V21)
INSERT INTO visit_services (id, visit_id, pet_id, service_type, is_completed, created_at, updated_at)
VALUES ('efefefef-0000-0000-0000-000000000050', 'efefefef-0000-0000-0000-000000000040', 'efefefef-0000-0000-0000-000000000006', 'FEEDING', FALSE, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;
