-- Existing installations only. New databases already include this in schema.sql.
BEGIN;
ALTER TABLE user_profile ADD COLUMN IF NOT EXISTS loc_country_code VARCHAR(2);
COMMIT;
