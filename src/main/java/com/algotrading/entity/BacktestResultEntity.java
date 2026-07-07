package com.algotrading.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.LocalDateTime;

/** Per-strategy backtest summary — the same shape as the JSON `data[]` rows. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "backtest_result")
public class BacktestResultEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(nullable = false)
    private String strategy;

    private String mode;

    private int skipped;
    private int symbols;
    private int trades;
    private int wins;
    private int losses;

    @Column(name = "win_rate")
    private double winRate;

    @Column(name = "gross_pnl")
    private double grossPnl;

    @Column(name = "total_charges")
    private double totalCharges;

    @Column(name = "net_pnl")
    private double netPnl;

    @Column(name = "avg_pnl")
    private double avgPnl;

    @Column(name = "avg_r")
    private double avgR;

    @Column(name = "profit_factor")
    private double profitFactor;

    @Column(name = "best_trade")
    private double bestTrade;

    @Column(name = "worst_trade")
    private double worstTrade;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
