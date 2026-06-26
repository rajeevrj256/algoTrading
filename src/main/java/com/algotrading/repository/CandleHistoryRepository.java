package com.algotrading.repository;

import com.algotrading.entity.CandleHistoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface CandleHistoryRepository extends JpaRepository<CandleHistoryEntity, Long> {

    /** Upsert one bar — dedup on (symbol, ts), refresh OHLCV on conflict. */
    @Modifying
    @Query(value =
            "INSERT INTO candle_history (symbol, ts, open_price, high_price, low_price, close_price, volume) " +
            "VALUES (:symbol, :ts, :open, :high, :low, :close, :volume) " +
            "ON CONFLICT (symbol, ts) DO UPDATE SET " +
            "  open_price = EXCLUDED.open_price, high_price = EXCLUDED.high_price, " +
            "  low_price = EXCLUDED.low_price, close_price = EXCLUDED.close_price, volume = EXCLUDED.volume",
            nativeQuery = true)
    void upsert(@Param("symbol") String symbol,
                @Param("ts") LocalDateTime ts,
                @Param("open") double open,
                @Param("high") double high,
                @Param("low") double low,
                @Param("close") double close,
                @Param("volume") long volume);

    List<CandleHistoryEntity> findBySymbolOrderByTsAsc(String symbol);

    long countBySymbol(String symbol);
}
