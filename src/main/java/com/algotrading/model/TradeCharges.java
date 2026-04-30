package com.algotrading.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradeCharges {
    private double buyValue;
    private double sellValue;
    private double turnover;
    private double brokerage;
    private double stt;
    private double exchangeCharge;
    private double gst;
    private double sebi;
    private double stamp;
    private double totalCharges;
    private double grossPnl;
    private double netPnl;
}
