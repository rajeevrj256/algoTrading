package com.algotrading.dto;

import com.algotrading.enums.PositionStatus;
import com.algotrading.enums.SignalType;
import com.algotrading.enums.StrategyType;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
    private double         stopLoss;
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
}
