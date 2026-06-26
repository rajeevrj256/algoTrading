package com.algotrading.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * StrategyExpectancyDTO — realized performance of one strategy over a window.
 *
 * The point of this DTO is the feedback loop: run the engine for a week, then
 * disable any strategy whose {@code avgPnl} (expectancy ₹/trade) or {@code avgR}
 * is negative. avgR is the average outcome expressed in units of initial risk
 * (1R = the rupee distance entry→stop × quantity), so it is comparable across
 * symbols and position sizes.
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class StrategyExpectancyDTO {
    private String  strategy;
    private long    trades;
    private long    wins;
    private long    losses;
    private double  winRate;       // %
    private double  totalPnl;      // ₹ net, after charges
    private double  avgPnl;        // ₹ per trade = expectancy
    private double  totalCharges;  // ₹ paid in costs
    private double  avgR;          // average outcome in R multiples
    private double  bestTrade;     // ₹
    private double  worstTrade;    // ₹
}
