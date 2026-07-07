package com.algotrading.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * BacktestResultDTO — aggregated performance of one strategy over replayed
 * historical candles. Same shape/meaning as StrategyExpectancyDTO (live trades)
 * so you can compare backtest vs reality directly.
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class BacktestResultDTO {
    private String  strategy;
    private String  mode;           // EQUITY (cash) or FNO (index options)
    private int     skipped;        // FNO: signals dropped — no Groww option data / no contract
    private int     symbols;        // how many symbols contributed trades
    private int     trades;
    private int     wins;
    private int     losses;
    private double  winRate;        // %
    private double  grossPnl;       // ₹ before charges
    private double  totalCharges;   // ₹
    private double  netPnl;         // ₹ after charges
    private double  avgPnl;         // ₹/trade = expectancy (net)
    private double  avgR;           // average outcome in R (net of charges)
    private double  profitFactor;   // gross wins / gross losses (net)
    private double  bestTrade;      // ₹
    private double  worstTrade;     // ₹
}
