package com.algotrading.repository;

import com.algotrading.entity.IndexCandleHistoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface IndexCandleHistoryRepository extends JpaRepository<IndexCandleHistoryEntity, Long> {

    /** Upsert one index bar — dedup on (symbol, ts), refresh OHLCV on conflict. */
    @Modifying
    @Query(value =
            "INSERT INTO index_candle_history (symbol, ts, open_price, high_price, low_price, close_price, volume) " +
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

    List<IndexCandleHistoryEntity> findBySymbolOrderByTsAsc(String symbol);

    List<IndexCandleHistoryEntity> findBySymbolAndTsBetweenOrderByTsAsc(String symbol, LocalDateTime from, LocalDateTime to);

    long countBySymbol(String symbol);
}
