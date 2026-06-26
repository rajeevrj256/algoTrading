package com.algotrading.event;

import com.algotrading.dto.AlertDTO;
import com.algotrading.dto.CandleDTO;
import com.algotrading.dto.DailySummaryDTO;
import com.algotrading.dto.PositionDTO;
import com.algotrading.service.CandleHistoryService;
import com.algotrading.service.NotificationService;
import com.algotrading.service.SheetsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
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
    private static final String TOPIC_CANDLES       = "algotrading.candle-ingest";

    @Value("${app.kafka.enabled:false}")
    private boolean kafkaEnabled;

    private final ObjectProvider<KafkaTemplate<String, Object>> kafkaTemplateProvider;
    private final SheetsService sheetsService;
    private final NotificationService notificationService;
    private final CandleHistoryService candleHistoryService;

    // ── Candle ingest (→ CandleHistoryService / candle_history) ─

    /** Persist freshly-fetched candles. Fire-and-forget; falls back to direct save. */
    public void publishCandles(String symbol, List<CandleDTO> candles) {
        if (symbol == null || candles == null || candles.isEmpty()) return;
        if (!shouldUseKafka()) {
            candleHistoryService.saveAll(symbol, candles);
            return;
        }
        CandleIngestEvent event = CandleIngestEvent.builder()
                .symbol(symbol)
                .candles(candles)
                .timestamp(LocalDateTime.now())
                .build();
        send(TOPIC_CANDLES, symbol, event);
    }

    // ── Reporting events (→ SheetsService / PostgreSQL) ──────

    public void publishTradeLog(PositionDTO position) {
        if (!shouldUseKafka()) {
            sheetsService.logTrade(position);
            return;
        }
        TradeReportingEvent event = TradeReportingEvent.builder()
                .type(TradeReportingType.LOG_TRADE)
                .key(position.getSymbol())
                .position(position)
                .timestamp(LocalDateTime.now())
                .build();
        send(TOPIC_REPORTING, event.getKey(), event);
    }

    public void publishOpenPositionsUpdate(List<PositionDTO> positions) {
        if (!shouldUseKafka()) {
            sheetsService.updateOpenPositions(positions != null ? positions : Collections.emptyList());
            return;
        }
        TradeReportingEvent event = TradeReportingEvent.builder()
                .type(TradeReportingType.UPDATE_OPEN_POSITIONS)
                .key("SYSTEM")
                .openPositions(positions != null ? positions : Collections.emptyList())
                .timestamp(LocalDateTime.now())
                .build();
        send(TOPIC_REPORTING, event.getKey(), event);
    }

    public void publishDailySummaryUpdate(DailySummaryDTO summary) {
        if (!shouldUseKafka()) {
            sheetsService.updateDailySummary(summary);
            return;
        }
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
        if (!shouldUseKafka()) {
            notificationService.notifyTradeOpen(position);
            return;
        }
        TradeNotificationEvent event = TradeNotificationEvent.builder()
                .type(TradeNotificationType.TRADE_OPENED)
                .key(position.getSymbol())
                .position(position)
                .timestamp(LocalDateTime.now())
                .build();
        send(TOPIC_NOTIFICATIONS, event.getKey(), event);
    }

    public void publishTradeCloseNotification(PositionDTO position) {
        if (!shouldUseKafka()) {
            notificationService.notifyTradeClose(position);
            return;
        }
        TradeNotificationEvent event = TradeNotificationEvent.builder()
                .type(TradeNotificationType.TRADE_CLOSED)
                .key(position.getSymbol())
                .position(position)
                .timestamp(LocalDateTime.now())
                .build();
        send(TOPIC_NOTIFICATIONS, event.getKey(), event);
    }

    public void publishAlert(AlertDTO alert) {
        if (!shouldUseKafka()) {
            notificationService.sendAlert(alert);
            return;
        }
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
        KafkaTemplate<String, Object> kafkaTemplate = kafkaTemplateProvider.getIfAvailable();
        if (kafkaTemplate == null) {
            log.warn("[Kafka] Kafka is enabled but no KafkaTemplate is available. Falling back is skipped for {}", topic);
            return;
        }
        kafkaTemplate.send(topic, key, event)
                .addCallback(
                        result -> log.debug("[Kafka] Sent to {} key={}", topic, key),
                        ex -> log.error("[Kafka] FAILED to send to {} key={}: {}", topic, key, ex.getMessage())
                );
    }

    private boolean shouldUseKafka() {
        return kafkaEnabled && kafkaTemplateProvider.getIfAvailable() != null;
    }
}
