-- V26: Expand pet details with health status and update species
-- Add new status columns
ALTER TABLE pets ADD COLUMN neutered_status VARCHAR(20);
ALTER TABLE pets ADD COLUMN vaccination_status VARCHAR(20);
ALTER TABLE pets ADD COLUMN deworming_status VARCHAR(20);

-- Migrate existing is_neutered data
UPDATE pets SET neutered_status = CASE 
    WHEN is_neutered = true THEN 'YES' 
    WHEN is_neutered = false THEN 'NO' 
    ELSE 'NO' 
END;

-- Set default for existing records
UPDATE pets SET vaccination_status = 'NO' WHERE vaccination_status IS NULL;
UPDATE pets SET deworming_status = 'NO' WHERE deworming_status IS NULL;
UPDATE pets SET neutered_status = 'NO' WHERE neutered_status IS NULL;

-- Make columns NOT NULL
ALTER TABLE pets ALTER COLUMN neutered_status SET NOT NULL;
ALTER TABLE pets ALTER COLUMN vaccination_status SET NOT NULL;
ALTER TABLE pets ALTER COLUMN deworming_status SET NOT NULL;

-- Require gender (backfill existing with UNKNOWN)
UPDATE pets SET gender = 'UNKNOWN' WHERE gender IS NULL;
ALTER TABLE pets ALTER COLUMN gender SET NOT NULL;

-- Drop legacy column
ALTER TABLE pets DROP COLUMN is_neutered;
