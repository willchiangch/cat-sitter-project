-- V24: Add birth_date to pets table
-- Purpose: Support pet age calculation in UI

ALTER TABLE pets
ADD COLUMN IF NOT EXISTS birth_date DATE;

COMMENT ON COLUMN pets.birth_date IS '毛孩出生年月日';
