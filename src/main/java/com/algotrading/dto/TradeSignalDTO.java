package com.algotrading.dto;

import com.algotrading.enums.InstrumentType;
import com.algotrading.enums.OptionType;
import com.algotrading.enums.SignalType;
import com.algotrading.enums.StrategyType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

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

    // ── F&O (index option) fields — null/0 for cash-equity signals ──
    // For option signals: symbol = option trading symbol, entryPrice/stopLoss/target
    // are PREMIUM values; the underlying* fields keep the index-frame levels that
    // actually drive exit decisions.
    private InstrumentType instrumentType;
    private String         underlying;        // scan symbol of the index (e.g. NIFTY_50)
    private OptionType     optionType;
    private double         strike;
    private LocalDate      expiry;
    private int            lotSize;
    private int            lots;
    private double         underlyingEntry;
    private double         underlyingStop;
    private double         underlyingTarget;
}
