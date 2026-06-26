-- Strategy enable/disable, persisted so toggles survive restarts and can be
-- changed at runtime (no redeploy). Loaded into memory once at startup and on
-- demand via POST /api/strategy/config/refresh.
CREATE TABLE strategy_config (
    strategy   VARCHAR(32) PRIMARY KEY,   -- matches StrategyType enum name
    enabled    BOOLEAN     NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMP            DEFAULT NOW()
);

-- Seed all known strategies. VWAP_MR disabled: realized 0 wins / 14 trades,
-- avgR -0.895 (mean-reversion buying reclaims in a downtrend).
INSERT INTO strategy_config (strategy, enabled) VALUES
    ('ORB',        TRUE),
    ('VWAP_MR',    FALSE),
    ('EMA_CROSS',  TRUE),
    ('SUPERTREND', TRUE),
    ('GAP_GO',     TRUE);
