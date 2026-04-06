-- Add dummy account for onboarding E2E test
INSERT INTO accounts (id, email, password_hash, oauth_provider, status, created_at, updated_at)
VALUES ('efefefef-0000-0000-0000-000000000003', 'newbie@example.com', '$2a$10$vX8V.F9z9K.Wv9Hl6UfD.e6yA6z6y6y6y6y6y6y6y6y6y6y6y6y6', 'GOOGLE', 'ACTIVE', NOW(), NOW())
ON CONFLICT (id) DO NOTHING;
