-- Separate store for F&O (index option) premium candles, keyed by the option
-- trading symbol (e.g. NIFTY2570824500CE). Equity/index CASH candles stay in
-- candle_history; option OHLCV lives here so F&O data is isolated and easy to query.
-- Populated by the F&O backtest (GrowwHistoricalService.optionCandles); upsert dedups
-- on (symbol, ts).
CREATE TABLE fno_candle_history (
    id          BIGSERIAL PRIMARY KEY,
    symbol      VARCHAR(50)      NOT NULL,   -- option trading symbol
    ts          TIMESTAMP        NOT NULL,
    open_price  DOUBLE PRECISION NOT NULL,
    high_price  DOUBLE PRECISION NOT NULL,
    low_price   DOUBLE PRECISION NOT NULL,
    close_price DOUBLE PRECISION NOT NULL,
    volume      BIGINT           NOT NULL DEFAULT 0,
    CONSTRAINT uq_fno_candle_symbol_ts UNIQUE (symbol, ts)
);

CREATE INDEX idx_fno_candle_symbol_ts ON fno_candle_history(symbol, ts);
