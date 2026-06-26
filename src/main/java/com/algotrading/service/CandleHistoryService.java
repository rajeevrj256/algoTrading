package com.algotrading.service;

import com.algotrading.dto.CandleDTO;

import java.util.List;

/**
 * CandleHistoryService — persists live candles and serves them back for backtests.
 *
 * Implementation: CandleHistoryServiceImpl
 */
public interface CandleHistoryService {

    /** Upsert a batch of candles for a symbol (deduped on symbol+timestamp). */
    void saveAll(String symbol, List<CandleDTO> candles);

    /** Load up to {@code limit} most-recent stored candles, ascending by time. */
    List<CandleDTO> load(String symbol, int limit);

    /** How many bars are stored for a symbol. */
    long count(String symbol);
}
