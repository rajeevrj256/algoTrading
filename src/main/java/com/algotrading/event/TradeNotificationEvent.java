package com.algotrading.event;

import com.algotrading.dto.AlertDTO;
import com.algotrading.dto.PositionDTO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Event published to the "algotrading.trade-notifications" Kafka topic.
 *
 * Consumed by TradeNotificationConsumer which delegates to NotificationService (Telegram).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradeNotificationEvent {

    private TradeNotificationType type;

    /** Kafka message key — symbol name or "SYSTEM" for generic alerts. */
    private String key;

    /** Non-null for TRADE_OPENED / TRADE_CLOSED. */
    private PositionDTO position;

    /** Non-null for ALERT. */
    private AlertDTO alert;

    private LocalDateTime timestamp;
}
