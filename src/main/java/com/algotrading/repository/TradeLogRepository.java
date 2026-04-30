package com.algotrading.repository;

import com.algotrading.entity.TradeLogEntity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface TradeLogRepository extends JpaRepository<TradeLogEntity, Long> {
    List<TradeLogEntity> findByTradeDate(LocalDate tradeDate);

    @Query(value = "SELECT COALESCE(MAX(CAST(SUBSTRING(position_id FROM 'POS-([0-9]+)') AS INTEGER)), 0) " +
            "FROM trade_log WHERE position_id ~ '^POS-[0-9]+$'", nativeQuery = true)
    Integer findMaxPositionSequence();
}
