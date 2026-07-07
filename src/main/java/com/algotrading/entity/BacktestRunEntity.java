package com.algotrading.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.LocalDateTime;

/** One row per /api/backtest/{equity,fno} invocation — the run header. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "backtest_run")
public class BacktestRunEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String mode;                 // EQUITY | FNO

    @Column(nullable = false)
    private String symbols;              // requested CSV

    @Column(name = "strategy_filter")
    private String strategyFilter;       // null = all strategies for the segment

    @Column(name = "window_days")
    private int windowDays;

    @Column(name = "total_strategies")
    private int totalStrategies;

    @Column(name = "total_trades")
    private int totalTrades;

    @Column(name = "gross_pnl")
    private double grossPnl;

    @Column(name = "total_charges")
    private double totalCharges;

    @Column(name = "net_pnl")
    private double netPnl;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
