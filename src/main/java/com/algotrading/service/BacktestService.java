package com.algotrading.service;

import com.algotrading.dto.BacktestResultDTO;
import com.algotrading.enums.StrategyType;

import java.util.List;

/**
 * BacktestService — replays stored historical candles through the strategies and
 * reports per-strategy expectancy, so a strategy can be validated WITHOUT waiting
 * a live trading window.
 *
 * Implementation: BacktestServiceImpl
 */
public interface BacktestService {

    /**
     * Backtest the given symbols.
     * @param symbols  symbols to fetch candles for and replay
     * @param type     a single strategy to test, or null for every loaded strategy
     * @param count    how many recent candles to pull per symbol
     */
    List<BacktestResultDTO> run(List<String> symbols, StrategyType type, int count);
}
