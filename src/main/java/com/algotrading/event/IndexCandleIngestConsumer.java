package com.algotrading.event;

import com.algotrading.service.IndexCandleHistoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * IndexCandleIngestConsumer — consumes "algotrading.index-candle-ingest" and upserts the
 * index candle batch into index_candle_history. Log + skip on error; never block the
 * partition. Mirrors CandleIngestConsumer / FnoCandleIngestConsumer.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class IndexCandleIngestConsumer {

    private final IndexCandleHistoryService indexCandleHistoryService;

    @KafkaListener(
            topics = "algotrading.index-candle-ingest",
            groupId = "algotrading-index-candle-ingest",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(CandleIngestEvent event) {
        try {
            indexCandleHistoryService.saveAll(event.getSymbol(), event.getCandles());
        } catch (Exception e) {
            log.error("[Kafka] Failed to ingest index candles for {}: {}",
                    event != null ? event.getSymbol() : "?", e.getMessage(), e);
        }
    }
}
