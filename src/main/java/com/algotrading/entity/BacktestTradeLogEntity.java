package com.algotrading.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.LocalDateTime;

/** One simulated EQUITY backtest trade — mirrors trade_log (entry/exit/side/pnl/charges/R). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "backtest_trade_log")
public class BacktestTradeLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    private String strategy;

    @Column(nullable = false)
    private String symbol;

    @Column(nullable = false)
    private String side;                 // BUY | SELL

    @Column(name = "entry_time")
    private LocalDateTime entryTime;

    @Column(name = "entry_price", nullable = false)
    private double entryPrice;

    @Column(name = "exit_time")
    private LocalDateTime exitTime;

    @Column(name = "exit_price")
    private double exitPrice;

    @Column(name = "stop_loss")
    private double stopLoss;

    private double target;
    private int quantity;

    @Column(name = "risk_amount")
    private double riskAmount;

    private double charges;

    @Column(name = "gross_pnl")
    private double grossPnl;

    private double pnl;                  // net

    @Column(name = "pnl_pct")
    private double pnlPct;

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
