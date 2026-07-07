package com.algotrading.enums;

/**
 * Live strategy set. Legacy negative-expectancy setups (ORB, ORB_RETEST, VWAP_MR,
 * EMA_CROSS, SUPERTREND, GAP_GO) were removed — charges beat their edge
 * (VWAP_MR: 0 wins / 14, avgR −0.895). Historic trade_log rows with those names
 * are tolerated (parsers null them out).
 */
public enum StrategyType {
    // Trend-aligned setups (built to beat charges: trend filter + ≥0.4% target + trail)
    VWAP_TREND,     // pullback to rising VWAP, ride the trend
    EMA_PULLBACK,   // stacked-EMA trend, buy pullback to EMA21
    ORB_REFINED,    // opening-range breakout, only in gap/bias direction
    INDEX_TREND     // index regime strategy: ADX + Supertrend + VWAP alignment (volume-free)
}
