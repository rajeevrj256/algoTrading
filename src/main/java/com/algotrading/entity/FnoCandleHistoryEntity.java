package com.algotrading.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * FnoCandleHistoryEntity — one stored 5-min option premium bar, keyed by the option
 * trading symbol. Mirrors CandleHistoryEntity but lives in fno_candle_history so F&O
 * candle data stays isolated from equity/index (CASH) candles.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "fno_candle_history",
        uniqueConstraints = @UniqueConstraint(name = "uq_fno_candle_symbol_ts", columnNames = {"symbol", "ts"}))
public class FnoCandleHistoryEntity {

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
