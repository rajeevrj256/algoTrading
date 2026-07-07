package com.algotrading.service;

import com.algotrading.dto.CandleDTO;
import com.algotrading.dto.TradeSignalDTO;
import com.algotrading.enums.Segment;
import com.algotrading.enums.StrategyType;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
     * Confluence run limited to the strategies assigned to {@code segment} (EQUITY or
     * FNO); a strategy tagged BOTH runs in either. Used by the parallel engine passes.
     */
    Optional<TradeSignalDTO> runStrategies(String symbol, List<CandleDTO> candles, Segment segment);

    /**
     * Registered strategy types assigned to {@code segment} (segment or BOTH),
     * regardless of enabled state — used by the per-segment backtest to pick its set.
     */
    Set<StrategyType> strategiesForSegment(Segment segment);

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
