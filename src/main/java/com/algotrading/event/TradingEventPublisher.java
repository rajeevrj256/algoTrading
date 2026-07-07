package com.algotrading.event;

import com.algotrading.dto.AlertDTO;
import com.algotrading.dto.CandleDTO;
import com.algotrading.dto.DailySummaryDTO;
import com.algotrading.dto.PositionDTO;
import com.algotrading.service.CandleHistoryService;
import com.algotrading.service.FnoCandleHistoryService;
import com.algotrading.service.IndexCandleHistoryService;
import com.algotrading.service.NotificationService;
import com.algotrading.service.SheetsService;
import com.algotrading.util.Symbols;
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
    private static final String TOPIC_INDEX_CANDLES = "algotrading.index-candle-ingest";
    private static final String TOPIC_FNO_CANDLES   = "algotrading.fno-candle-ingest";

    @Value("${app.kafka.enabled:false}")
    private boolean kafkaEnabled;

    private final ObjectProvider<KafkaTemplate<String, Object>> kafkaTemplateProvider;
    private final SheetsService sheetsService;
    private final NotificationService notificationService;
    private final CandleHistoryService candleHistoryService;
    private final IndexCandleHistoryService indexCandleHistoryService;
    private final FnoCandleHistoryService fnoCandleHistoryService;

    // ── Candle ingest (→ CandleHistoryService / candle_history) ─

    /**
     * Persist freshly-fetched CASH candles. Routes by symbol type (live AND backtest):
     * an INDEX symbol (NIFTY, BANKNIFTY, ...) → index_candle_history under the canonical
     * name; anything else (equity) → candle_history. Uses Kafka when enabled AND the send
     * succeeds; otherwise falls back to a **direct DB save** so candles are never lost.
     */
    public void publishCandles(String symbol, List<CandleDTO> candles) {
        if (symbol == null || candles == null || candles.isEmpty()) return;
        String canonical = Symbols.canonicalIndex(symbol);
        boolean isIndex = canonical != null;
        String key = isIndex ? canonical : symbol;   // index stored under canonical name
        String topic = isIndex ? TOPIC_INDEX_CANDLES : TOPIC_CANDLES;
        if (shouldUseKafka()) {
            CandleIngestEvent event = CandleIngestEvent.builder()
                    .symbol(key).candles(candles).timestamp(LocalDateTime.now()).build();
            if (trySend(topic, key, event)) return;
            log.warn("[Kafka] candle send failed — saving {} bars for {} directly", candles.size(), key);
        }
        if (isIndex) indexCandleHistoryService.saveAll(key, candles);
        else candleHistoryService.saveAll(key, candles);
    }

    /**
     * Persist freshly-fetched F&O (option premium) candles to fno_candle_history.
     * Same rule as equity candles: Kafka when it works, direct DB save otherwise.
     */
    public void publishFnoCandles(String symbol, List<CandleDTO> candles) {
        if (symbol == null || candles == null || candles.isEmpty()) return;
        if (shouldUseKafka()) {
            CandleIngestEvent event = CandleIngestEvent.builder()
                    .symbol(symbol).candles(candles).timestamp(LocalDateTime.now()).build();
            if (trySend(TOPIC_FNO_CANDLES, symbol, event)) return;
            log.warn("[Kafka] FNO candle send failed — saving {} bars for {} directly", candles.size(), symbol);
        }
        fnoCandleHistoryService.saveAll(symbol, candles);
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

    /** Fire-and-forget send (reporting/notifications) — drop on failure, never propagate. */
    private void send(String topic, String key, Object event) {
        trySend(topic, key, event);
    }

    /**
     * Attempt a Kafka send. Returns true if handed off to the producer, false if no template
     * or the send threw synchronously (broker unreachable → metadata timeout). Callers that
     * must not lose data (candle persistence) use the false return to fall back to a direct save.
     */
    private boolean trySend(String topic, String key, Object event) {
        KafkaTemplate<String, Object> kafkaTemplate = kafkaTemplateProvider.getIfAvailable();
        if (kafkaTemplate == null) {
            log.warn("[Kafka] enabled but no KafkaTemplate available for {}", topic);
            return false;
        }
        try {
            kafkaTemplate.send(topic, key, event)
                    .addCallback(
                            result -> log.debug("[Kafka] Sent to {} key={}", topic, key),
                            ex -> log.error("[Kafka] FAILED to send to {} key={}: {}", topic, key, ex.getMessage())
                    );
            return true;
        } catch (Exception e) {
            log.error("[Kafka] send to {} threw (broker down?): {}", topic, e.getMessage());
            return false;
        }
    }

    private boolean shouldUseKafka() {
        return kafkaEnabled && kafkaTemplateProvider.getIfAvailable() != null;
    }
}
