package com.algotrading.dto;

import com.algotrading.enums.SignalType;
import com.algotrading.enums.StrategyType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradeSignalDTO {
    private String       symbol;
    private SignalType   signal;
    private StrategyType strategy;
    private double       entryPrice;
    private double       stopLoss;
    private double       target;
    private int          quantity;
    private double       confidence;
    private double       rrRatio;
    private String       signalReason;
    private String       whyFull;
    private String       indRsi;
    private String       indEmaGap;
    private String       indVwapDev;
    private String       indVolRatio;
    private String       indAtr;
    private String       indExtra;
}
