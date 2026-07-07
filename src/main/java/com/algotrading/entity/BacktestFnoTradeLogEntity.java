package com.algotrading.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** One simulated F&O (index option) backtest trade — mirrors fno_trade_log. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "backtest_fno_trade_log")
public class BacktestFnoTradeLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    private String strategy;

    @Column(nullable = false)
    private String symbol;               // option groww_symbol

    private String underlying;

    @Column(name = "option_type")
    private String optionType;           // CE | PE

    private double strike;

    private LocalDate expiry;

    @Column(name = "lot_size")
    private int lotSize;

    private int lots;

    @Column(nullable = false)
    private String side;                 // BUY (long option)

    @Column(name = "entry_time")
    private LocalDateTime entryTime;

    @Column(name = "entry_price", nullable = false)
    private double entryPrice;           // premium

    @Column(name = "exit_time")
    private LocalDateTime exitTime;

    @Column(name = "exit_price")
    private double exitPrice;            // premium

    @Column(name = "premium_stop")
    private double premiumStop;

    private int quantity;                // lots * lotSize
    private double charges;

    @Column(name = "gross_pnl")
    private double grossPnl;

    private double pnl;                  // net

    @Column(name = "risk_reward")
    private double riskReward;           // outcome in R (net)

    @Column(name = "exit_reason")
    private String exitReason;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
