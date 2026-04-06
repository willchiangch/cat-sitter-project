-- Rename effective_date to effective_start_date and add effective_end_date
ALTER TABLE services RENAME COLUMN effective_date TO effective_start_date;
ALTER TABLE services ADD COLUMN IF NOT EXISTS effective_end_date DATE;
