-- F&O (index options) support.
--
-- open_position gains the option contract descriptor + the underlying-frame
-- exit levels (decisions run on the index, fills on the premium) + trail state
-- so a restart doesn't lose the breakeven/trailing ratchet.
-- trade_log gains the contract descriptor for reporting/expectancy.

ALTER TABLE open_position
    ADD COLUMN initial_stop            DOUBLE PRECISION NOT NULL DEFAULT 0,
    ADD COLUMN peak_price              DOUBLE PRECISION NOT NULL DEFAULT 0,
    ADD COLUMN instrument_type         VARCHAR(20),
    ADD COLUMN underlying              VARCHAR(50),
    ADD COLUMN option_type             VARCHAR(4),
    ADD COLUMN strike                  DOUBLE PRECISION NOT NULL DEFAULT 0,
    ADD COLUMN expiry                  DATE,
    ADD COLUMN lot_size                INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN lots                    INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN underlying_entry        DOUBLE PRECISION NOT NULL DEFAULT 0,
    ADD COLUMN underlying_stop         DOUBLE PRECISION NOT NULL DEFAULT 0,
    ADD COLUMN underlying_initial_stop DOUBLE PRECISION NOT NULL DEFAULT 0,
    ADD COLUMN underlying_target       DOUBLE PRECISION NOT NULL DEFAULT 0,
    ADD COLUMN underlying_peak         DOUBLE PRECISION NOT NULL DEFAULT 0;

ALTER TABLE trade_log
    ADD COLUMN instrument_type VARCHAR(20),
    ADD COLUMN underlying      VARCHAR(50),
    ADD COLUMN option_type     VARCHAR(4),
    ADD COLUMN strike          DOUBLE PRECISION NOT NULL DEFAULT 0,
    ADD COLUMN expiry          DATE,
    ADD COLUMN lot_size        INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN lots            INTEGER NOT NULL DEFAULT 0;

-- The legacy negative-expectancy strategies were deleted from the codebase
-- (ORB, ORB_RETEST, VWAP_MR, EMA_CROSS, SUPERTREND, GAP_GO) — drop their config
-- rows so the toggle API only shows real strategies.
DELETE FROM strategy_config
 WHERE strategy IN ('ORB', 'ORB_RETEST', 'VWAP_MR', 'EMA_CROSS', 'SUPERTREND', 'GAP_GO');

-- New regime-filtered index momentum strategy (volume-free, ADX + Supertrend + VWAP).
INSERT INTO strategy_config (strategy, enabled) VALUES ('INDEX_TREND', TRUE)
ON CONFLICT (strategy) DO UPDATE SET enabled = TRUE;
