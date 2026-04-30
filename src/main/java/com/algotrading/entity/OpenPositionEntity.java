package com.algotrading.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "open_position")
public class OpenPositionEntity {

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

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
