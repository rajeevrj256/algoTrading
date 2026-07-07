package com.algotrading.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * IndexCandleHistoryEntity — one stored 5-min INDEX (underlying) CASH bar, keyed by the
 * canonical index name (NIFTY, BANKNIFTY, ...). Mirrors CandleHistoryEntity but lives in
 * index_candle_history so index underlying data stays isolated from equity (candle_history)
 * and option premium (fno_candle_history) candles.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "index_candle_history",
        uniqueConstraints = @UniqueConstraint(name = "uq_index_candle_symbol_ts", columnNames = {"symbol", "ts"}))
public class IndexCandleHistoryEntity {

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
