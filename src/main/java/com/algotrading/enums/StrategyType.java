package com.algotrading.enums;

public enum StrategyType {
    ORB,
    VWAP_MR,
    EMA_CROSS,
    SUPERTREND,
    GAP_GO,
    // Trend-aligned setups (built to beat charges: trend filter + ≥0.4% target + trail)
    VWAP_TREND,     // pullback to rising VWAP, ride the trend
    EMA_PULLBACK,   // stacked-EMA trend, buy pullback to EMA21
    ORB_REFINED     // opening-range breakout, only in gap/bias direction
}
