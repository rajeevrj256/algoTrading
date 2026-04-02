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
@Table(name = "daily_summary")
public class DailySummaryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "summary_date", nullable = false, unique = true)
    private String summaryDate;

    private String strategy;
    private int trades;
    private int wins;
    private int losses;

    @Column(name = "win_rate")
    private double winRate;

    @Column(name = "total_pnl")
    private double totalPnl;

    @Column(name = "best_trade")
    private double bestTrade;

    @Column(name = "worst_trade")
    private double worstTrade;

    @Column(name = "avg_pnl")
    private double avgPnl;

    @Column(name = "circuit_tripped")
    private boolean circuitTripped;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
