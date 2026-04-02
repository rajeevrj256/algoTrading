package com.algotrading.event;

import com.algotrading.dto.DailySummaryDTO;
import com.algotrading.dto.PositionDTO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Event published to the "algotrading.trade-reporting" Kafka topic.
 *
 * Consumed by TradeReportingConsumer which delegates to SheetsService (PostgreSQL).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradeReportingEvent {

    private TradeReportingType type;

    /** Kafka message key — symbol name or "SYSTEM" for non-symbol events. */
    private String key;

    /** Non-null for LOG_TRADE. */
    private PositionDTO position;

    /** Non-null for UPDATE_OPEN_POSITIONS. */
    private List<PositionDTO> openPositions;

    /** Non-null for UPDATE_DAILY_SUMMARY. */
    private DailySummaryDTO dailySummary;

    private LocalDateTime timestamp;
}
