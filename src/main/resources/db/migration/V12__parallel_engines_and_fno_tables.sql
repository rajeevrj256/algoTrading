-- Parallel EQUITY + F&O engines with separated strategies and separated order tables.
--
-- 1. strategy_config gains a `segment` so each strategy is assigned to the equity
--    engine, the F&O engine, or both. Default EQUITY; INDEX_TREND → FNO.
-- 2. F&O orders/tracking move to their own tables (fno_open_position / fno_trade_log),
--    mirroring the equity open_position / trade_log shape (incl. the V11 option columns).
--    Equity tables then hold cash trades only.

-- ── 1. per-segment strategy assignment ────────────────────────
ALTER TABLE strategy_config
    ADD COLUMN segment VARCHAR(10) NOT NULL DEFAULT 'EQUITY';

UPDATE strategy_config SET segment = 'FNO'
 WHERE strategy = 'INDEX_TREND';

UPDATE strategy_config SET segment = 'EQUITY'
 WHERE strategy IN ('VWAP_TREND', 'EMA_PULLBACK', 'ORB_REFINED');

-- ── 2a. F&O open positions (live option legs) ─────────────────
CREATE TABLE fno_open_position (
    id                      BIGSERIAL PRIMARY KEY,
    position_id             VARCHAR(50),
    symbol                  VARCHAR(50)  NOT NULL,
    side                    VARCHAR(10)  NOT NULL,
    strategy                VARCHAR(30),
    entry_price             DOUBLE PRECISION NOT NULL,
    stop_loss               DOUBLE PRECISION,
    target                  DOUBLE PRECISION,
    quantity                INTEGER      NOT NULL,
    entry_time              TIMESTAMP,
    signal_reason           TEXT,
    ind_rsi                 VARCHAR(50),
    ind_ema_gap             VARCHAR(50),
    ind_vwap_dev            VARCHAR(50),
    ind_vol_ratio           VARCHAR(50),
    ind_atr                 VARCHAR(50),
    ind_extra               VARCHAR(255),
    why_full                TEXT,
    initial_stop            DOUBLE PRECISION NOT NULL DEFAULT 0,
    peak_price              DOUBLE PRECISION NOT NULL DEFAULT 0,
    instrument_type         VARCHAR(20),
    underlying              VARCHAR(50),
    option_type             VARCHAR(4),
    strike                  DOUBLE PRECISION NOT NULL DEFAULT 0,
    expiry                  DATE,
    lot_size                INTEGER NOT NULL DEFAULT 0,
    lots                    INTEGER NOT NULL DEFAULT 0,
    underlying_entry        DOUBLE PRECISION NOT NULL DEFAULT 0,
    underlying_stop         DOUBLE PRECISION NOT NULL DEFAULT 0,
    underlying_initial_stop DOUBLE PRECISION NOT NULL DEFAULT 0,
    underlying_target       DOUBLE PRECISION NOT NULL DEFAULT 0,
    underlying_peak         DOUBLE PRECISION NOT NULL DEFAULT 0,
    created_at              TIMESTAMP    DEFAULT NOW()
);

-- ── 2b. F&O closed trades (option history) ────────────────────
CREATE TABLE fno_trade_log (
    id              BIGSERIAL PRIMARY KEY,
    trade_date      DATE,
    trade_time      TIME,
    position_id     VARCHAR(50),
    symbol          VARCHAR(50)  NOT NULL,
    side            VARCHAR(10)  NOT NULL,
    strategy        VARCHAR(30),
    entry_price     DOUBLE PRECISION NOT NULL,
    exit_price      DOUBLE PRECISION,
    stop_loss       DOUBLE PRECISION,
    target          DOUBLE PRECISION,
    quantity        INTEGER      NOT NULL,
    risk_amount     DOUBLE PRECISION,
    reward_amount   DOUBLE PRECISION,
    charges         DOUBLE PRECISION,
    pnl             DOUBLE PRECISION,
    pnl_pct         DOUBLE PRECISION,
    risk_reward     DOUBLE PRECISION,
    exit_reason     VARCHAR(255),
    hold_duration   VARCHAR(100),
    signal_reason   TEXT,
    ind_rsi         VARCHAR(50),
    ind_ema_gap     VARCHAR(50),
    ind_vwap_dev    VARCHAR(50),
    ind_vol_ratio   VARCHAR(50),
    ind_atr         VARCHAR(50),
    ind_extra       VARCHAR(255),
    why_full        TEXT,
    instrument_type VARCHAR(20),
    underlying      VARCHAR(50),
    option_type     VARCHAR(4),
    strike          DOUBLE PRECISION NOT NULL DEFAULT 0,
    expiry          DATE,
    lot_size        INTEGER NOT NULL DEFAULT 0,
    lots            INTEGER NOT NULL DEFAULT 0,
    status          VARCHAR(20)  DEFAULT 'CLOSED',
    created_at      TIMESTAMP    DEFAULT NOW()
);

CREATE INDEX idx_fno_trade_log_date     ON fno_trade_log(trade_date);
CREATE INDEX idx_fno_trade_log_strategy ON fno_trade_log(strategy);
CREATE INDEX idx_fno_open_position_sym  ON fno_open_position(symbol);
