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

-- 4. Ensure James has a pet named 'Fluffy' for E2E tests
-- Fixed: removed non-existent breed/weight, using species/weight_kg based on V1/V2 schema
INSERT INTO pets (id, client_profile_id, name, species, gender, weight_kg, is_neutered, created_at, updated_at)
VALUES 
('efefefef-0000-0000-0000-000000000005', 'efefefef-0000-0000-0000-000000000002', 'Fluffy', 'CAT', 'FEMALE', 4.5, TRUE, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- 5. Whitelist James for Sophia (Required for booking-lifecycle.spec.js)
INSERT INTO sitter_client_whitelists (id, sitter_profile_id, client_profile_id, skip_questionnaire, notes, created_at, updated_at)
VALUES ('efefefef-0000-0000-0000-000000000010', 'efefefef-0000-0000-0000-000000000001', 'efefefef-0000-0000-0000-000000000002', TRUE, 'E2E Test WhiteList', NOW(), NOW())
ON CONFLICT (id) DO NOTHING;
