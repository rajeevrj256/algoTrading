package com.algotrading.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * FnoOpenPositionEntity — live F&O (index option) legs. Mirrors OpenPositionEntity
 * (incl. the option-contract + underlying-frame + trail columns) but persists to the
 * separate fno_open_position table so equity and F&O tracking stay isolated.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "fno_open_position")
public class FnoOpenPositionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "position_id")
    private String positionId;

    @Column(nullable = false)
    private String symbol;

    @Column(nullable = false)
    private String side;

    private String strategy;

    @Column(name = "entry_price", nullable = false)
    private double entryPrice;

    @Column(name = "stop_loss")
    private double stopLoss;

    private double target;
    private int quantity;

    @Column(name = "entry_time")
    private LocalDateTime entryTime;

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

    @Column(name = "initial_stop")
    private double initialStop;

    @Column(name = "peak_price")
    private double peakPrice;

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

    @Column(name = "underlying_entry")
    private double underlyingEntry;

    @Column(name = "underlying_stop")
    private double underlyingStop;

    @Column(name = "underlying_initial_stop")
    private double underlyingInitialStop;

    @Column(name = "underlying_target")
    private double underlyingTarget;

    @Column(name = "underlying_peak")
    private double underlyingPeak;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
