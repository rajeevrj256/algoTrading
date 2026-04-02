package com.algotrading.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class DailySummaryDTO {
    private String  date;
    private String  strategy;
    private int     trades;
    private int     wins;
    private int     losses;
    private double  winRate;
    private double  totalPnl;
    private double  bestTrade;
    private double  worstTrade;
    private double  avgPnl;
    private boolean circuitTripped;
}
