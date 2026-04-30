ALTER TABLE trade_log
    ADD COLUMN IF NOT EXISTS position_id VARCHAR(50);

CREATE INDEX IF NOT EXISTS idx_trade_log_position_id
    ON trade_log(position_id);

ALTER TABLE open_position
    ADD COLUMN IF NOT EXISTS ind_rsi TEXT,
    ADD COLUMN IF NOT EXISTS ind_ema_gap TEXT,
    ADD COLUMN IF NOT EXISTS ind_vwap_dev TEXT,
    ADD COLUMN IF NOT EXISTS ind_vol_ratio TEXT,
    ADD COLUMN IF NOT EXISTS ind_atr TEXT,
    ADD COLUMN IF NOT EXISTS ind_extra TEXT,
    ADD COLUMN IF NOT EXISTS why_full TEXT;
