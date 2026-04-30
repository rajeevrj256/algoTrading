package com.algotrading.event;

import com.algotrading.service.SheetsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * TradeReportingConsumer — consumes events from "algotrading.trade-reporting".
 *
 * Delegates to SheetsService (PostgreSQL) for trade logging, open positions refresh,
 * and daily summary updates.
 *
 * Error handling: log + skip. A failed DB write must never crash the consumer
 * or block the Kafka partition.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class TradeReportingConsumer {

    private final SheetsService sheetsService;

    @KafkaListener(
            topics = "algotrading.trade-reporting",
            groupId = "algotrading-reporting",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(TradeReportingEvent event) {
        try {
            log.debug("[Kafka] Received reporting event type={} key={}", event.getType(), event.getKey());

            switch (event.getType()) {
                case LOG_TRADE:
                    sheetsService.logTrade(event.getPosition());
                    break;
                case UPDATE_OPEN_POSITIONS:
                    sheetsService.updateOpenPositions(event.getOpenPositions());
                    break;
                case UPDATE_DAILY_SUMMARY:
                    sheetsService.updateDailySummary(event.getDailySummary());
                    break;
                default:
                    log.warn("[Kafka] Unknown reporting event type: {}", event.getType());
            }
        } catch (Exception e) {
            log.error("[Kafka] Failed to process reporting event type={}: {}",
                    event.getType(), e.getMessage(), e);
        }
    }
}
