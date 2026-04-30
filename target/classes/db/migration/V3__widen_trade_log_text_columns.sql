ALTER TABLE trade_log
    ALTER COLUMN hold_duration TYPE VARCHAR(100),
    ALTER COLUMN ind_rsi TYPE TEXT,
    ALTER COLUMN ind_ema_gap TYPE TEXT,
    ALTER COLUMN ind_vwap_dev TYPE TEXT,
    ALTER COLUMN ind_vol_ratio TYPE TEXT,
    ALTER COLUMN ind_atr TYPE TEXT,
    ALTER COLUMN ind_extra TYPE TEXT;
