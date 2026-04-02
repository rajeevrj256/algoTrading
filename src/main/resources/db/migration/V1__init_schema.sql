-- Trade Log: one row per closed trade
CREATE TABLE trade_log (
    id              BIGSERIAL PRIMARY KEY,
    trade_date      DATE,
    trade_time      TIME,
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
    pnl             DOUBLE PRECISION,
    pnl_pct         DOUBLE PRECISION,
    risk_reward     DOUBLE PRECISION,
    exit_reason     VARCHAR(255),
    hold_duration   VARCHAR(50),
    signal_reason   TEXT,
    ind_rsi         VARCHAR(50),
    ind_ema_gap     VARCHAR(50),
    ind_vwap_dev    VARCHAR(50),
    ind_vol_ratio   VARCHAR(50),
    ind_atr         VARCHAR(50),
    ind_extra       VARCHAR(255),
    why_full        TEXT,
    status          VARCHAR(20)  DEFAULT 'CLOSED',
    created_at      TIMESTAMP    DEFAULT NOW()
);

-- Open Positions: current live positions (cleared and refreshed each scan)
CREATE TABLE open_position (
    id              BIGSERIAL PRIMARY KEY,
    symbol          VARCHAR(50)  NOT NULL,
    side            VARCHAR(10)  NOT NULL,
    strategy        VARCHAR(30),
    entry_price     DOUBLE PRECISION NOT NULL,
    stop_loss       DOUBLE PRECISION,
    target          DOUBLE PRECISION,
    quantity        INTEGER      NOT NULL,
    entry_time      TIMESTAMP,
    signal_reason   TEXT,
    created_at      TIMESTAMP    DEFAULT NOW()
);

-- Daily Summary: one row per trading day
CREATE TABLE daily_summary (
    id              BIGSERIAL PRIMARY KEY,
    summary_date    VARCHAR(20)  NOT NULL UNIQUE,
    strategy        VARCHAR(30),
    trades          INTEGER      DEFAULT 0,
    wins            INTEGER      DEFAULT 0,
    losses          INTEGER      DEFAULT 0,
    win_rate        DOUBLE PRECISION DEFAULT 0,
    total_pnl       DOUBLE PRECISION DEFAULT 0,
    best_trade      DOUBLE PRECISION DEFAULT 0,
    worst_trade     DOUBLE PRECISION DEFAULT 0,
    avg_pnl         DOUBLE PRECISION DEFAULT 0,
    circuit_tripped BOOLEAN      DEFAULT FALSE,
    created_at      TIMESTAMP    DEFAULT NOW()
);

CREATE INDEX idx_trade_log_symbol ON trade_log(symbol);
CREATE INDEX idx_trade_log_date ON trade_log(trade_date);
CREATE INDEX idx_daily_summary_date ON daily_summary(summary_date);
