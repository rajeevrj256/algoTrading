ALTER TABLE open_position
    ADD COLUMN IF NOT EXISTS position_id VARCHAR(50);

CREATE INDEX IF NOT EXISTS idx_open_position_position_id
    ON open_position(position_id);
