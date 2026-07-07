-- Restore the designed strategy→engine assignment after live F&O losses on
-- 2026-07-07: EMA_PULLBACK (widened to F&O by a manual segment change) entered two
-- correlated index-option shorts on a paper-thin EMA stack in midday chop and lost
-- on premium stops. INDEX_TREND's ADX(14) ≥ 20 regime gate exists precisely to block
-- that kind of no-trend entry — F&O goes back to INDEX_TREND only.
--
-- Equity strategies keep trading the cash segment. To re-widen a strategy to F&O
-- deliberately, set segment='BOTH' and hit POST /api/strategy/config/refresh.
UPDATE strategy_config SET segment = 'FNO'
 WHERE strategy = 'INDEX_TREND';

UPDATE strategy_config SET segment = 'EQUITY'
 WHERE strategy IN ('VWAP_TREND', 'EMA_PULLBACK', 'ORB_REFINED');
