package com.algotrading.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * CandleHistoryEntity — one stored 5-min OHLCV bar. Unique per (symbol, ts);
 * upserts refresh OHLCV so the in-progress bar matures correctly.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "candle_history",
        uniqueConstraints = @UniqueConstraint(name = "uq_candle_symbol_ts", columnNames = {"symbol", "ts"}))
public class CandleHistoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String symbol;

    @Column(nullable = false)
    private LocalDateTime ts;

    @Column(name = "open_price", nullable = false)
    private double open;

    @Column(name = "high_price", nullable = false)
    private double high;

    @Column(name = "low_price", nullable = false)
    private double low;

    @Column(name = "close_price", nullable = false)
    private double close;

    @Column(nullable = false)
    private long volume;
}
