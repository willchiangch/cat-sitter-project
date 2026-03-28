-- V9: Add last_active_role to accounts table to support role switching persistency
ALTER TABLE accounts ADD COLUMN last_active_role VARCHAR(50);
UPDATE accounts SET last_active_role = 'CLIENT' WHERE last_active_role IS NULL;
