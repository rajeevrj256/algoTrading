package com.algotrading.service;

import com.algotrading.dto.CandleDTO;

import java.util.List;
import java.util.Optional;

/**
 * DataFeedService — contract for fetching NSE OHLCV candle data.
 *
 * Implementation: YahooFinanceDataFeedService
 */
public interface DataFeedService {

    /**
     * Fetch the latest N 5-minute candles for a NSE symbol.
     * Returns empty list if data is unavailable.
     */
    List<CandleDTO> getCandles(String symbol, int count);

    /**
     * Get the last traded price for a symbol.
     */
    Optional<Double> getLastPrice(String symbol);

    /**
     * Check if the data feed provider is reachable.
     */
    boolean isAvailable();

    /**
     * Evict the local TTL cache for a symbol, forcing a fresh fetch.
     */
    void evictCache(String symbol);
}
