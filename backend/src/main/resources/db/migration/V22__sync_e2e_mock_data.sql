-- Task: Synchronize E2E mock data for cloud testing
-- Ensure James (Client) and Sophia (Sitter) accounts exist with correct fixed UUIDs

-- 1. SOPHIA (Sitter) Account
INSERT INTO accounts (id, email, password_hash, oauth_provider, status, created_at, updated_at)
VALUES ('efefefef-0000-0000-0000-000000000001', 'sophia@example.com', 'MOCK_HASH', 'GOOGLE', 'ACTIVE', NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- 2. JAMES (Client) Account
INSERT INTO accounts (id, email, password_hash, oauth_provider, status, created_at, updated_at)
VALUES ('efefefef-0000-0000-0000-000000000002', 'james@example.com', 'MOCK_HASH', 'GOOGLE', 'ACTIVE', NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- 3. Profiles for Sophia and James
INSERT INTO profiles (id, account_id, nickname, role_type, gender, created_at, updated_at)
VALUES 
('efefefef-0000-0000-0000-000000000001', 'efefefef-0000-0000-0000-000000000001', 'Sophia Sitter', 'SITTER', 'FEMALE', NOW(), NOW()),
('efefefef-0000-0000-0000-000000000002', 'efefefef-0000-0000-0000-000000000002', 'James Wilson', 'CLIENT', 'MALE', NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- 4. Ensure Sophia has a Sitter Profile Extension
INSERT INTO sitter_profiles (id, bio, service_introduction, experience_years, created_at, updated_at)
VALUES ('efefefef-0000-0000-0000-000000000001', 'I am a pro sitter.', 'Pro services.', 5, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- 5. Ensure James has a pet named 'Fluffy' for E2E tests
INSERT INTO pets (id, owner_profile_id, name, species, breed, gender, weight, is_neutered, created_at, updated_at)
VALUES 
('efefefef-0000-0000-0000-000000000005', 'efefefef-0000-0000-0000-000000000002', 'Fluffy', 'CAT', 'British Shorthair', 'FEMALE', 4.5, TRUE, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;
