CREATE TABLE health_check_log (
    id             BIGSERIAL PRIMARY KEY,
    check_number   INTEGER      NOT NULL,
    overall_status VARCHAR(10)  NOT NULL,
    ok_count       INTEGER      NOT NULL,
    warn_count     INTEGER      NOT NULL,
    fail_count     INTEGER      NOT NULL,
    items_json     TEXT,
    checked_at     TIMESTAMP    DEFAULT NOW()
);

CREATE INDEX idx_health_check_log_checked_at ON health_check_log(checked_at);
CREATE INDEX idx_health_check_log_status ON health_check_log(overall_status);
