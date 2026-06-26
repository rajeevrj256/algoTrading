-- Historical 5-min OHLCV captured live from the data feed, so backtests get
-- real depth instead of the feed's ~5-day window. Written via Kafka
-- (algotrading.candle-ingest) → CandleIngestConsumer, deduped on (symbol, ts).
CREATE TABLE candle_history (
    id          BIGSERIAL PRIMARY KEY,
    symbol      VARCHAR(50)      NOT NULL,
    ts          TIMESTAMP        NOT NULL,
    open_price  DOUBLE PRECISION NOT NULL,
    high_price  DOUBLE PRECISION NOT NULL,
    low_price   DOUBLE PRECISION NOT NULL,
    close_price DOUBLE PRECISION NOT NULL,
    volume      BIGINT           NOT NULL DEFAULT 0,
    CONSTRAINT uq_candle_symbol_ts UNIQUE (symbol, ts)
);

CREATE INDEX idx_candle_symbol_ts ON candle_history(symbol, ts);
