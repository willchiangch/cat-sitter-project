-- Add ical_token to sitter_calendar_configs for public ICS feed authentication
ALTER TABLE sitter_calendar_configs ADD COLUMN ical_token VARCHAR(64) UNIQUE;

-- Initialize ical_token for existing rows (optional, if any)
UPDATE sitter_calendar_configs SET ical_token = encode(digest(random()::text, 'sha256'), 'hex') WHERE ical_token IS NULL;
