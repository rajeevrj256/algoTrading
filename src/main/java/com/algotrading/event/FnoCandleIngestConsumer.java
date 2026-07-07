package com.algotrading.event;

import com.algotrading.service.FnoCandleHistoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * FnoCandleIngestConsumer — consumes "algotrading.fno-candle-ingest" and upserts the
 * option candle batch into fno_candle_history. Log + skip on error; never block the
 * partition. Mirrors CandleIngestConsumer (equity/index candles).
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class FnoCandleIngestConsumer {

    private final FnoCandleHistoryService fnoCandleHistoryService;

    @KafkaListener(
            topics = "algotrading.fno-candle-ingest",
            groupId = "algotrading-fno-candle-ingest",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(CandleIngestEvent event) {
        try {
            fnoCandleHistoryService.saveAll(event.getSymbol(), event.getCandles());
        } catch (Exception e) {
            log.error("[Kafka] Failed to ingest F&O candles for {}: {}",
                    event != null ? event.getSymbol() : "?", e.getMessage(), e);
        }
    }
}
