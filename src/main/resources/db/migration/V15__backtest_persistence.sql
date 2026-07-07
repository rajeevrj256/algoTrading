-- Backtest persistence.
--
-- Every /api/backtest/equity and /api/backtest/fno run now writes to the DB the same
-- way the live flow does: a run header, one per-strategy result row (the JSON response),
-- and one row PER SIMULATED TRADE (entry/exit/side/pnl/charges/R/exit-reason) — split
-- into equity vs F&O tables to mirror trade_log / fno_trade_log.
--
-- The fetched OHLC used by the replay is already persisted to candle_history /
-- fno_candle_history by the Groww historical fetch (deduped on symbol+ts); these tables
-- add the trade + result layer on top so a backtest is fully inspectable in SQL.

-- ── Run header — one row per backtest invocation ──────────────
CREATE TABLE backtest_run (
    id               BIGSERIAL PRIMARY KEY,
    mode             VARCHAR(10)      NOT NULL,          -- EQUITY | FNO
    symbols          VARCHAR(500)     NOT NULL,          -- CSV as requested
    strategy_filter  VARCHAR(40),                        -- NULL = all strategies for the segment
    window_days      INTEGER          NOT NULL DEFAULT 0,
    total_strategies INTEGER          NOT NULL DEFAULT 0,
    total_trades     INTEGER          NOT NULL DEFAULT 0,
    gross_pnl        DOUBLE PRECISION NOT NULL DEFAULT 0,
    total_charges    DOUBLE PRECISION NOT NULL DEFAULT 0,
    net_pnl          DOUBLE PRECISION NOT NULL DEFAULT 0,
    created_at       TIMESTAMP        NOT NULL DEFAULT now()
);

-- ── Per-strategy summary — the rows returned in the JSON `data` array ──
CREATE TABLE backtest_result (
    id            BIGSERIAL PRIMARY KEY,
    run_id        BIGINT           NOT NULL,
    strategy      VARCHAR(40)      NOT NULL,
    mode          VARCHAR(10),
    skipped       INTEGER          NOT NULL DEFAULT 0,
    symbols       INTEGER          NOT NULL DEFAULT 0,
    trades        INTEGER          NOT NULL DEFAULT 0,
    wins          INTEGER          NOT NULL DEFAULT 0,
    losses        INTEGER          NOT NULL DEFAULT 0,
    win_rate      DOUBLE PRECISION NOT NULL DEFAULT 0,
    gross_pnl     DOUBLE PRECISION NOT NULL DEFAULT 0,
    total_charges DOUBLE PRECISION NOT NULL DEFAULT 0,
    net_pnl       DOUBLE PRECISION NOT NULL DEFAULT 0,
    avg_pnl       DOUBLE PRECISION NOT NULL DEFAULT 0,
    avg_r         DOUBLE PRECISION NOT NULL DEFAULT 0,
    profit_factor DOUBLE PRECISION NOT NULL DEFAULT 0,
    best_trade    DOUBLE PRECISION NOT NULL DEFAULT 0,
    worst_trade   DOUBLE PRECISION NOT NULL DEFAULT 0,
    created_at    TIMESTAMP        NOT NULL DEFAULT now()
);

-- ── Equity simulated trades (mirror trade_log) ────────────────
CREATE TABLE backtest_trade_log (
    id           BIGSERIAL PRIMARY KEY,
    run_id       BIGINT           NOT NULL,
    strategy     VARCHAR(40),
    symbol       VARCHAR(50)      NOT NULL,
    side         VARCHAR(4)       NOT NULL,               -- BUY | SELL
    entry_time   TIMESTAMP,
    entry_price  DOUBLE PRECISION NOT NULL,
    exit_time    TIMESTAMP,
    exit_price   DOUBLE PRECISION NOT NULL DEFAULT 0,
    stop_loss    DOUBLE PRECISION NOT NULL DEFAULT 0,
    target       DOUBLE PRECISION NOT NULL DEFAULT 0,
    quantity     INTEGER          NOT NULL DEFAULT 0,
    risk_amount  DOUBLE PRECISION NOT NULL DEFAULT 0,
    charges      DOUBLE PRECISION NOT NULL DEFAULT 0,
    gross_pnl    DOUBLE PRECISION NOT NULL DEFAULT 0,
    pnl          DOUBLE PRECISION NOT NULL DEFAULT 0,     -- net (after charges)
    pnl_pct      DOUBLE PRECISION NOT NULL DEFAULT 0,
    risk_reward  DOUBLE PRECISION NOT NULL DEFAULT 0,     -- outcome in R (net)
    exit_reason  VARCHAR(30),
    created_at   TIMESTAMP        NOT NULL DEFAULT now()
);

-- ── F&O (index option) simulated trades (mirror fno_trade_log) ──
CREATE TABLE backtest_fno_trade_log (
    id           BIGSERIAL PRIMARY KEY,
    run_id       BIGINT           NOT NULL,
    strategy     VARCHAR(40),
    symbol       VARCHAR(60)      NOT NULL,               -- option groww_symbol
    underlying   VARCHAR(50),
    option_type  VARCHAR(4),                              -- CE | PE
    strike       DOUBLE PRECISION NOT NULL DEFAULT 0,
    expiry       DATE,
    lot_size     INTEGER          NOT NULL DEFAULT 0,
    lots         INTEGER          NOT NULL DEFAULT 0,
    side         VARCHAR(4)       NOT NULL,               -- always BUY (long option)
    entry_time   TIMESTAMP,
    entry_price  DOUBLE PRECISION NOT NULL,               -- premium
    exit_time    TIMESTAMP,
    exit_price   DOUBLE PRECISION NOT NULL DEFAULT 0,     -- premium
    premium_stop DOUBLE PRECISION NOT NULL DEFAULT 0,
    quantity     INTEGER          NOT NULL DEFAULT 0,     -- lots * lot_size
    charges      DOUBLE PRECISION NOT NULL DEFAULT 0,
    gross_pnl    DOUBLE PRECISION NOT NULL DEFAULT 0,
    pnl          DOUBLE PRECISION NOT NULL DEFAULT 0,     -- net (after charges)
    risk_reward  DOUBLE PRECISION NOT NULL DEFAULT 0,     -- outcome in R (net)
    exit_reason  VARCHAR(30),
    created_at   TIMESTAMP        NOT NULL DEFAULT now()
);

CREATE INDEX idx_backtest_result_run  ON backtest_result(run_id);
CREATE INDEX idx_backtest_trade_run   ON backtest_trade_log(run_id);
CREATE INDEX idx_backtest_fno_trade_run ON backtest_fno_trade_log(run_id);
