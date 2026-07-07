-- Separate store for INDEX (underlying) CASH candles — NIFTY, BANKNIFTY, FINNIFTY,
-- MIDCPNIFTY — keyed by the canonical index name. Equity CASH candles stay in
-- candle_history; option premium candles live in fno_candle_history; index underlying
-- candles now live here so the three data sets are fully isolated.
--
-- Populated for BOTH paths, routed by symbol type in TradingEventPublisher.publishCandles:
--   - live  : the data feed fetches index candles each scan
--   - backtest: the F&O backtest fetches the underlying via GrowwHistoricalService.equityCandles
-- Upsert dedups on (symbol, ts).
CREATE TABLE index_candle_history (
    id          BIGSERIAL PRIMARY KEY,
    symbol      VARCHAR(50)      NOT NULL,   -- canonical index name (NIFTY, BANKNIFTY, ...)
    ts          TIMESTAMP        NOT NULL,
    open_price  DOUBLE PRECISION NOT NULL,
    high_price  DOUBLE PRECISION NOT NULL,
    low_price   DOUBLE PRECISION NOT NULL,
    close_price DOUBLE PRECISION NOT NULL,
    volume      BIGINT           NOT NULL DEFAULT 0,
    CONSTRAINT uq_index_candle_symbol_ts UNIQUE (symbol, ts)
);

CREATE INDEX idx_index_candle_symbol_ts ON index_candle_history(symbol, ts);
