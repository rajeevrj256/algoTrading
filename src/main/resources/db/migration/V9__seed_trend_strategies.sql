-- Switch the live scan to the trend-aligned strategies. The legacy setups had
-- negative net expectancy (charges > edge) or no trend filter — disable them.
-- Re-enable any via POST /api/strategy/config/{type}/enable after backtesting.
UPDATE strategy_config
   SET enabled = FALSE
 WHERE strategy IN ('ORB', 'VWAP_MR', 'EMA_CROSS', 'SUPERTREND', 'GAP_GO');

INSERT INTO strategy_config (strategy, enabled) VALUES
    ('VWAP_TREND',   TRUE),
    ('EMA_PULLBACK', TRUE),
    ('ORB_REFINED',  TRUE);
