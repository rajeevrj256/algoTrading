package com.algotrading.event;

import com.algotrading.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * TradeNotificationConsumer — consumes events from "algotrading.trade-notifications".
 *
 * Delegates to NotificationService (Telegram) for trade open/close notifications
 * and generic alerts.
 *
 * Error handling: log + skip. A Telegram failure must never crash the consumer
 * or block the Kafka partition.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TradeNotificationConsumer {

    private final NotificationService notificationService;

    @KafkaListener(
            topics = "algotrading.trade-notifications",
            groupId = "algotrading-notifications",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(TradeNotificationEvent event) {
        try {
            log.debug("[Kafka] Received notification event type={} key={}", event.getType(), event.getKey());

            switch (event.getType()) {
                case TRADE_OPENED:
                    notificationService.notifyTradeOpen(event.getPosition());
                    break;
                case TRADE_CLOSED:
                    notificationService.notifyTradeClose(event.getPosition());
                    break;
                case ALERT:
                    notificationService.sendAlert(event.getAlert());
                    break;
                default:
                    log.warn("[Kafka] Unknown notification event type: {}", event.getType());
            }
        } catch (Exception e) {
            log.error("[Kafka] Failed to process notification event type={}: {}",
                    event.getType(), e.getMessage(), e);
        }
    }
}
