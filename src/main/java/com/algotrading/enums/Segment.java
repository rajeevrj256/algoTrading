package com.algotrading.enums;

/**
 * Segment — which trading engine a strategy (and its orders) belong to.
 *
 *   EQUITY : intraday cash-equity scan → equity tables (open_position / trade_log)
 *   FNO    : index-option scan         → F&O tables    (fno_open_position / fno_trade_log)
 *   BOTH   : strategy runs in both engine passes
 *
 * Assigned per strategy in the strategy_config.segment column; the equity and F&O
 * engines run as independent passes each scan, toggled by trading.equity.enabled /
 * trading.fno.enabled in application.yml.
 */
public enum Segment {
    EQUITY,
    FNO,
    BOTH
}
