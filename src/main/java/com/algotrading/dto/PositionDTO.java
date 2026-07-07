package com.algotrading.dto;

import com.algotrading.enums.InstrumentType;
import com.algotrading.enums.OptionType;
import com.algotrading.enums.PositionStatus;
import com.algotrading.enums.SignalType;
import com.algotrading.enums.StrategyType;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PositionDTO {
    private String         positionId;
    private String         symbol;
    private double         entryPrice;
    private double         exitPrice;
    private double         stopLoss;       // live stop — trailing logic moves this
    private double         initialStop;    // original stop at entry; defines 1R risk (immutable)
    private double         peakPrice;      // best favorable price seen; drives the trail
    private double         target;
    private int            quantity;
    private SignalType     signal;
    private StrategyType   strategy;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime  entryTime;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime  exitTime;

    private double         grossPnl;
    private double         charges;
    private double         pnl;
    private double         pnlPct;
    private PositionStatus status;
    private String         exitReason;
    private String         signalReason;
    private String         whyFull;
    private String         indRsi;
    private String         indEmaGap;
    private String         indVwapDev;
    private String         indVolRatio;
    private String         indAtr;
    private String         indExtra;

    // ── F&O (index option) fields — null/0 for cash-equity positions ──
    // symbol/entryPrice/stopLoss/target are in PREMIUM terms for options.
    // Exit DECISIONS run on the underlying index frame below; fills happen at
    // the option LTP. underlyingStop trails; underlyingInitialStop is immutable 1R.
    private InstrumentType instrumentType;
    private String         underlying;             // scan symbol of the index (e.g. NIFTY_50)
    private OptionType     optionType;
    private double         strike;
    private LocalDate      expiry;
    private int            lotSize;
    private int            lots;
    private double         underlyingEntry;
    private double         underlyingStop;         // live index-frame stop (trailing moves this)
    private double         underlyingInitialStop;  // original index-frame stop; defines 1R
    private double         underlyingTarget;
    private double         underlyingPeak;         // best favorable index price; drives the trail

    public boolean isOption() {
        return instrumentType == InstrumentType.INDEX_OPTION;
    }
}
