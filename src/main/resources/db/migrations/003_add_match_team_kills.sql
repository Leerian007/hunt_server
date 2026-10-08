-- hunt_sync_client.py also exports team_kills.
BEGIN;
ALTER TABLE match_record ADD COLUMN IF NOT EXISTS team_kills INTEGER NOT NULL DEFAULT 0 CHECK (team_kills >= 0);
COMMIT;
