package com.algotrading.service;

import com.algotrading.dto.CandleDTO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * IndexCandleHistoryService — stores/serves INDEX (underlying) CASH candles in the
 * index_candle_history table, keyed by the canonical index name. Separate from
 * CandleHistoryService (equity CASH) and FnoCandleHistoryService (option premium) so the
 * three data sets stay isolated. Writes are upserts (dedup on symbol+ts).
 *
 * Implementation: IndexCandleHistoryServiceImpl
 */
public interface IndexCandleHistoryService {

    /** Upsert a batch of index candles for one canonical index symbol. */
    void saveAll(String symbol, List<CandleDTO> candles);

    /** Most-recent {@code limit} stored bars for a symbol, ascending by time. */
    List<CandleDTO> load(String symbol, int limit);

    /** Stored bars for a symbol within [from, to], ascending by time. */
    List<CandleDTO> loadRange(String symbol, LocalDateTime from, LocalDateTime to);

    long count(String symbol);
}
