package com.algotrading.service;

import com.algotrading.dto.CandleDTO;
import com.algotrading.dto.TradeSignalDTO;
import com.algotrading.enums.StrategyType;

import java.util.List;
import java.util.Optional;

/**
 * TradingStrategy — contract every strategy must fulfil.
 *
 * Implementations (all in service/impl/):
 *   OrbStrategy, VwapMeanReversionStrategy, EmaCrossStrategy,
 *   SupertrendStrategy, GapAndGoStrategy
 */
public interface TradingStrategy {

    /**
     * Analyse candles and return a signal if conditions are met.
     * Returns Optional.empty() when no signal fires.
     */
    Optional<TradeSignalDTO> generate(String symbol, List<CandleDTO> candles);

    /** The strategy type this implementation handles. */
    StrategyType getType();

    /** Minimum number of candles required before this strategy can run. */
    int minCandles();
}
