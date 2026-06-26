package com.algotrading.repository;

import com.algotrading.entity.TradeLogEntity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface TradeLogRepository extends JpaRepository<TradeLogEntity, Long> {
    List<TradeLogEntity> findByTradeDate(LocalDate tradeDate);

    @Query(value = "SELECT COALESCE(MAX(CAST(SUBSTRING(position_id FROM 'POS-([0-9]+)') AS INTEGER)), 0) " +
            "FROM trade_log WHERE position_id ~ '^POS-[0-9]+$'", nativeQuery = true)
    Integer findMaxPositionSequence();

    /**
     * Per-strategy realized performance over the last :days days.
     * Columns (ordered): strategy, trades, wins, losses, totalPnl, avgPnl,
     * totalCharges, avgR, bestTrade, worstTrade. Mapped in RiskServiceImpl.
     * avgR weights each trade by its own initial risk (entry→stop × qty).
     */
    @Query(value =
            "SELECT COALESCE(strategy, 'UNKNOWN') AS strategy, " +
            "       COUNT(*) AS trades, " +
            "       COUNT(*) FILTER (WHERE pnl > 0) AS wins, " +
            "       COUNT(*) FILTER (WHERE pnl < 0) AS losses, " +
            "       COALESCE(SUM(pnl), 0) AS total_pnl, " +
            "       COALESCE(AVG(pnl), 0) AS avg_pnl, " +
            "       COALESCE(SUM(charges), 0) AS total_charges, " +
            "       COALESCE(AVG(pnl / NULLIF(ABS(entry_price - stop_loss) * quantity, 0)), 0) AS avg_r, " +
            "       COALESCE(MAX(pnl), 0) AS best_trade, " +
            "       COALESCE(MIN(pnl), 0) AS worst_trade " +
            "FROM trade_log " +
            "WHERE status = 'CLOSED' " +
            "  AND trade_date >= (CURRENT_DATE - CAST(:days AS integer)) " +
            "GROUP BY strategy " +
            "ORDER BY avg_pnl DESC",
            nativeQuery = true)
    List<Object[]> findStrategyExpectancy(@Param("days") int days);
}
