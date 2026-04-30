CREATE TABLE intraday_symbol (
    id           BIGSERIAL PRIMARY KEY,
    symbol       VARCHAR(50) NOT NULL UNIQUE,
    source       VARCHAR(50),
    score        DOUBLE PRECISION DEFAULT 0,
    active       BOOLEAN DEFAULT TRUE,
    notes        TEXT,
    selected_at  TIMESTAMP,
    valid_until  TIMESTAMP,
    created_at   TIMESTAMP DEFAULT NOW(),
    updated_at   TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_intraday_symbol_active_valid_until
    ON intraday_symbol(active, valid_until);

CREATE INDEX idx_intraday_symbol_score
    ON intraday_symbol(score DESC);
