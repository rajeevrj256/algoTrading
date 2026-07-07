package com.algotrading.service;

import com.algotrading.dto.CandleDTO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * FnoCandleHistoryService — stores/serves F&O (index option) premium candles in the
 * fno_candle_history table. Separate from CandleHistoryService (equity/index CASH
 * candles) so the two data sets stay isolated. Writes are upserts (dedup on symbol+ts).
 *
 * Implementation: FnoCandleHistoryServiceImpl
 */
public interface FnoCandleHistoryService {

    /** Upsert a batch of option candles for one option trading symbol. */
    void saveAll(String symbol, List<CandleDTO> candles);

    /** Most-recent {@code limit} stored bars for a symbol, ascending by time. */
    List<CandleDTO> load(String symbol, int limit);

    /** Stored bars for a symbol within [from, to], ascending by time. */
    List<CandleDTO> loadRange(String symbol, LocalDateTime from, LocalDateTime to);

    long count(String symbol);
}
