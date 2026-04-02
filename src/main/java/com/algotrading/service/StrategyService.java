package com.algotrading.service;

import com.algotrading.dto.CandleDTO;
import com.algotrading.dto.TradeSignalDTO;
import com.algotrading.enums.StrategyType;

import java.util.List;
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
}
