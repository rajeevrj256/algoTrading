package com.algotrading.event;

import com.algotrading.dto.CandleDTO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Event published to "algotrading.candle-ingest" — a batch of freshly-fetched
 * candles to persist. Consumed by CandleIngestConsumer → CandleHistoryService.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CandleIngestEvent {
    private String symbol;
    private List<CandleDTO> candles;
    private LocalDateTime timestamp;
}
