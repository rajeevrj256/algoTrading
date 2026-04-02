package com.algotrading.event;

import com.algotrading.dto.AlertDTO;
import com.algotrading.dto.DailySummaryDTO;
import com.algotrading.dto.PositionDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * TradingEventPublisher — publishes non-critical events to Kafka topics.
 *
 * Called by ScanOrchestrationServiceImpl instead of direct SheetsService/NotificationService calls.
 * All sends are async fire-and-forget: errors are logged but never thrown.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TradingEventPublisher {

    private static final String TOPIC_REPORTING     = "algotrading.trade-reporting";
    private static final String TOPIC_NOTIFICATIONS = "algotrading.trade-notifications";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    // ── Reporting events (→ SheetsService / PostgreSQL) ──────

    public void publishTradeLog(PositionDTO position) {
        TradeReportingEvent event = TradeReportingEvent.builder()
                .type(TradeReportingType.LOG_TRADE)
                .key(position.getSymbol())
                .position(position)
                .timestamp(LocalDateTime.now())
                .build();
        send(TOPIC_REPORTING, event.getKey(), event);
    }

    public void publishOpenPositionsUpdate(List<PositionDTO> positions) {
        TradeReportingEvent event = TradeReportingEvent.builder()
                .type(TradeReportingType.UPDATE_OPEN_POSITIONS)
                .key("SYSTEM")
                .openPositions(positions != null ? positions : Collections.emptyList())
                .timestamp(LocalDateTime.now())
                .build();
        send(TOPIC_REPORTING, event.getKey(), event);
    }

    public void publishDailySummaryUpdate(DailySummaryDTO summary) {
        TradeReportingEvent event = TradeReportingEvent.builder()
                .type(TradeReportingType.UPDATE_DAILY_SUMMARY)
                .key("SYSTEM")
                .dailySummary(summary)
                .timestamp(LocalDateTime.now())
                .build();
        send(TOPIC_REPORTING, event.getKey(), event);
    }

    // ── Notification events (→ NotificationService / Telegram) ─

    public void publishTradeOpenNotification(PositionDTO position) {
        TradeNotificationEvent event = TradeNotificationEvent.builder()
                .type(TradeNotificationType.TRADE_OPENED)
                .key(position.getSymbol())
                .position(position)
                .timestamp(LocalDateTime.now())
                .build();
        send(TOPIC_NOTIFICATIONS, event.getKey(), event);
    }

    public void publishTradeCloseNotification(PositionDTO position) {
        TradeNotificationEvent event = TradeNotificationEvent.builder()
                .type(TradeNotificationType.TRADE_CLOSED)
                .key(position.getSymbol())
                .position(position)
                .timestamp(LocalDateTime.now())
                .build();
        send(TOPIC_NOTIFICATIONS, event.getKey(), event);
    }

    public void publishAlert(AlertDTO alert) {
        TradeNotificationEvent event = TradeNotificationEvent.builder()
                .type(TradeNotificationType.ALERT)
                .key(alert.getSymbol() != null ? alert.getSymbol() : "SYSTEM")
                .alert(alert)
                .timestamp(LocalDateTime.now())
                .build();
        send(TOPIC_NOTIFICATIONS, event.getKey(), event);
    }

    // ── Internal ─────────────────────────────────────────────

    private void send(String topic, String key, Object event) {
        kafkaTemplate.send(topic, key, event)
                .addCallback(
                        result -> log.debug("[Kafka] Sent to {} key={}", topic, key),
                        ex -> log.error("[Kafka] FAILED to send to {} key={}: {}", topic, key, ex.getMessage())
                );
    }
}
