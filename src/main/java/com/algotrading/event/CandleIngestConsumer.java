package com.algotrading.event;

import com.algotrading.service.CandleHistoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * CandleIngestConsumer — consumes "algotrading.candle-ingest" and upserts the
 * candle batch into candle_history. Log + skip on error; never block the partition.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class CandleIngestConsumer {

    private final CandleHistoryService candleHistoryService;

    @KafkaListener(
            topics = "algotrading.candle-ingest",
            groupId = "algotrading-candle-ingest",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(CandleIngestEvent event) {
        try {
            candleHistoryService.saveAll(event.getSymbol(), event.getCandles());
        } catch (Exception e) {
            log.error("[Kafka] Failed to ingest candles for {}: {}",
                    event != null ? event.getSymbol() : "?", e.getMessage(), e);
        }
    }
}
