package com.algotrading.service;

import com.algotrading.dto.BacktestResultDTO;
import com.algotrading.entity.BacktestFnoTradeLogEntity;
import com.algotrading.entity.BacktestTradeLogEntity;
import com.algotrading.enums.StrategyType;

import java.util.List;

/**
 * Persists a completed backtest run to the DB (run header + per-strategy results +
 * every simulated trade), the same way the live flow logs trades. Best-effort: a
 * persistence failure never fails the backtest response.
 *
 * Implementation: BacktestPersistenceServiceImpl
 */
public interface BacktestPersistenceService {

    /**
     * @return the generated backtest_run id, or null if persistence failed/was skipped.
     */
    Long save(String mode, String symbols, StrategyType strategyFilter, int windowDays,
              List<BacktestResultDTO> results,
              List<BacktestTradeLogEntity> equityTrades,
              List<BacktestFnoTradeLogEntity> fnoTrades);
}
