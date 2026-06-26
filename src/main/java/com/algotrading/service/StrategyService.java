package com.algotrading.service;

import com.algotrading.dto.CandleDTO;
import com.algotrading.dto.TradeSignalDTO;
import com.algotrading.enums.StrategyType;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * StrategyService — orchestrates all registered TradingStrategy beans.
 *
 * Implementation: StrategyServiceImpl
 */
public interface StrategyService {

    /**
     * Run all registered strategies on the given candles.
     * Returns the first signal that fires (strategies run in priority order).
     */
    Optional<TradeSignalDTO> runStrategies(String symbol, List<CandleDTO> candles);

    /**
     * Run a specific named strategy only.
     */
    Optional<TradeSignalDTO> runStrategy(StrategyType type, String symbol, List<CandleDTO> candles);

    /**
     * Return the names of all loaded strategies.
     */
    List<String> listStrategies();

    /**
     * Current enabled/disabled state of every strategy (live in-memory view,
     * which mirrors the strategy_config table after the last load/refresh).
     */
    Map<String, Boolean> getStrategyStatus();

    /**
     * Enable or disable a strategy at runtime. Persists to strategy_config and
     * updates the in-memory set immediately — takes effect on the next scan,
     * no restart.
     */
    void setStrategyEnabled(StrategyType type, boolean enabled);

    /**
     * Reload the enabled set from strategy_config into memory.
     * Use after editing the table directly (outside the API).
     */
    void refreshEnabledStrategies();
}
