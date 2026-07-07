package com.algotrading.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "trade_log")
public class TradeLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trade_date")
    private LocalDate tradeDate;

    @Column(name = "trade_time")
    private LocalTime tradeTime;

    @Column(name = "position_id")
    private String positionId;

    @Column(nullable = false)
    private String symbol;

    @Column(nullable = false)
    private String side;

    private String strategy;

    @Column(name = "entry_price", nullable = false)
    private double entryPrice;

    @Column(name = "exit_price")
    private double exitPrice;

    @Column(name = "stop_loss")
    private double stopLoss;

    private double target;
    private int quantity;

    @Column(name = "risk_amount")
    private double riskAmount;

    @Column(name = "reward_amount")
    private double rewardAmount;

    private double charges;

    private double pnl;

    @Column(name = "pnl_pct")
    private double pnlPct;

    @Column(name = "risk_reward")
    private double riskReward;

    @Column(name = "exit_reason")
    private String exitReason;

    @Column(name = "hold_duration")
    private String holdDuration;

    @Column(name = "signal_reason")
    private String signalReason;

    @Column(name = "ind_rsi")
    private String indRsi;

    @Column(name = "ind_ema_gap")
    private String indEmaGap;

    @Column(name = "ind_vwap_dev")
    private String indVwapDev;

    @Column(name = "ind_vol_ratio")
    private String indVolRatio;

    @Column(name = "ind_atr")
    private String indAtr;

    @Column(name = "ind_extra")
    private String indExtra;

    @Column(name = "why_full")
    private String whyFull;

    // ── F&O (index option) fields ──
    @Column(name = "instrument_type")
    private String instrumentType;

    private String underlying;

    @Column(name = "option_type")
    private String optionType;

    private double strike;

    private LocalDate expiry;

    @Column(name = "lot_size")
    private int lotSize;

    private int lots;

    @Builder.Default
    private String status = "CLOSED";

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
