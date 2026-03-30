-- Seed data for H2 Smoke Testing (since Flyway is disabled)
-- Create Sophia (Sitter)
INSERT INTO accounts (id, email, password_hash, oauth_provider, status, is_email_verified)
VALUES ('efefefef-0000-0000-0000-000000000001', 'sophia@example.com', '$2a$10$vX8V.F9z9K.Wv9Hl6UfD.e6yA6z6y6y6y6y6y6y6y6y6y6y6y6y6', 'LOCAL', 'ACTIVE', true);

INSERT INTO profiles (id, account_id, name, role_type, is_verified)
VALUES ('efefefef-0000-0000-0000-000000000011', 'efefefef-0000-0000-0000-000000000001', 'Sophia Sitter', 'SITTER', true);

-- Create James (Client)
INSERT INTO accounts (id, email, password_hash, oauth_provider, status, is_email_verified)
VALUES ('efefefef-0000-0000-0000-000000000002', 'james@example.com', '$2a$10$vX8V.F9z9K.Wv9Hl6UfD.e6yA6z6y6y6y6y6y6y6y6y6y6y6y6y6', 'LOCAL', 'ACTIVE', true);

INSERT INTO profiles (id, account_id, name, role_type, is_verified)
VALUES ('efefefef-0000-0000-0000-000000000022', 'efefefef-0000-0000-0000-000000000002', 'James Client', 'CLIENT', true);

-- Create Newbie (No profiles)
INSERT INTO accounts (id, email, password_hash, oauth_provider, status, is_email_verified)
VALUES ('efefefef-0000-0000-0000-000000000003', 'newbie@example.com', '$2a$10$vX8V.F9z9K.Wv9Hl6UfD.e6yA6z6y6y6y6y6y6y6y6y6y6y6y6y6', 'GOOGLE', 'ACTIVE', true);
