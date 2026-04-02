package com.algotrading.service.impl;

import com.algotrading.dto.*;
import com.algotrading.enums.AlertType;
import com.algotrading.event.TradingEventPublisher;
import com.algotrading.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.apache.logging.log4j.util.Strings.repeat;

/**
 * ScanOrchestrationServiceImpl — implements ScanOrchestrationService.
 *
 * The main trading loop called by the scheduler every 5 minutes.
 * Orchestrates all services in sequence:
 *
 *   CRITICAL (synchronous):
 *     DataFeedService  → fetch candles
 *     StrategyService  → generate signal
 *     RiskService      → validate signal + system gate
 *     BrokerService    → open / check-exit positions
 *
 *   NON-CRITICAL (async via Kafka):
 *     SheetsService    → log trades and refresh open-positions tab
 *     NotificationService → send alerts
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScanOrchestrationServiceImpl implements ScanOrchestrationService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final DataFeedService       dataFeedService;
    private final StrategyService       strategyService;
    private final RiskService           riskService;
    private final BrokerService         brokerService;
    private final SheetsService         sheetsService;       // kept for printDailySummary() only
    private final TradingEventPublisher eventPublisher;

    @Value("${app.symbols:RELIANCE,TCS,INFY,HDFCBANK,ICICIBANK,AXISBANK,WIPRO,SBIN}")
    private String symbolsStr;

    @Value("${app.candle-count:120}")
    private int candleCount;

    // ── runScan ───────────────────────────────────────────────

    @Override
    public void runScan() {
        String time = LocalTime.now(IST).toString().substring(0, 8);
        log.info("[Engine] ===== SCAN START @ {} =====", time);

        // System-level risk gate
        RiskValidationDTO sysCheck = riskService.canTrade();
        if (!sysCheck.isApproved()) {
            log.info("[Engine] Scan skipped — {}", sysCheck.getReason());
            return;
        }

        String[] symbols = symbolsStr.split(",");

        for (String raw : symbols) {
            String symbol = raw.trim().toUpperCase();
            try {
                processSymbol(symbol);
            } catch (Exception e) {
                log.error("[Engine] Unexpected error processing {}: {}", symbol, e.getMessage(), e);
            }
        }

        // Refresh open positions tab (async via Kafka)
        eventPublisher.publishOpenPositionsUpdate(brokerService.getOpenPositions());
        log.info("[Engine] ===== SCAN END =====");
    }

    // ── runEod ────────────────────────────────────────────────

    @Override
    public void runEod() {
        log.info("[Engine] ===== EOD SQUARE-OFF =====");

        List<PositionDTO> openPos = brokerService.getOpenPositions();
        if (openPos.isEmpty()) {
            log.info("[Engine] No open positions to square off");
        } else {
            for (PositionDTO pos : openPos) {
                double price = dataFeedService.getLastPrice(pos.getSymbol())
                        .orElse(pos.getEntryPrice());

                // close via broker
                PositionDTO closed = brokerService.closePosition(
                        pos.getPositionId(), price, "EOD AUTO SQUARE-OFF");

                if (closed != null) {
                    riskService.recordTrade(closed.getPnl());
                    eventPublisher.publishTradeLog(closed);
                    eventPublisher.publishTradeCloseNotification(closed);
                }
            }
        }

        // Write daily summary (async via Kafka)
        DailySummaryDTO summary = riskService.getDailySummary();
        eventPublisher.publishDailySummaryUpdate(summary);

        // Clear open-positions tab (async via Kafka)
        eventPublisher.publishOpenPositionsUpdate(Collections.emptyList());

        // Send EOD summary alert (async via Kafka)
        eventPublisher.publishAlert(AlertDTO.builder()
                .type(AlertType.DAILY_SUMMARY)
                .title("Daily Summary")
                .message(String.format("Trades: %d | P&L: ₹%.2f | Win Rate: %.0f%%",
                        summary.getTrades(), summary.getTotalPnl(), summary.getWinRate()))
                .urgent(false)
                .build());

        printDailySummary(summary);
        log.info("[Engine] ===== EOD COMPLETE =====");
    }

    // ── Per-symbol logic ──────────────────────────────────────

    private void processSymbol(String symbol) {
        // 1. Check exits for any open position on this symbol
        Optional<Double> lastPrice = dataFeedService.getLastPrice(symbol);
        if (lastPrice.isPresent()) {
            List<PositionDTO> closed = brokerService.checkExits(symbol, lastPrice.get());
            for (PositionDTO c : closed) {
                riskService.recordTrade(c.getPnl());
                eventPublisher.publishTradeLog(c);
                eventPublisher.publishTradeCloseNotification(c);
                log.info("[Engine] Exit — {} P&L=₹{} | {}", symbol,
                        String.format("%.2f", c.getPnl()), c.getExitReason());
            }
        }

        // 2. Skip signal scan if already in a position for this symbol
        boolean alreadyOpen = brokerService.getOpenPositions().stream()
                .anyMatch(p -> symbol.equals(p.getSymbol()));
        if (alreadyOpen) {
            log.debug("[Engine] {} already has an open position — skipping signal scan", symbol);
            return;
        }

        // 3. Fetch candles
        List<CandleDTO> candles = dataFeedService.getCandles(symbol, candleCount);
        if (candles.size() < 30) {
            log.debug("[Engine] {} — insufficient candle data ({})", symbol, candles.size());
            return;
        }

        // 4. Run all strategies — first signal wins
        Optional<TradeSignalDTO> signalOpt = strategyService.runStrategies(symbol, candles);
        if (!signalOpt.isPresent()) {
            log.debug("[Engine] {} — no signal from any strategy", symbol);
            return;
        }
        TradeSignalDTO signal = signalOpt.get();

        // 5. Validate signal with risk service
        RiskValidationDTO validation = riskService.validateSignal(signal);
        if (!validation.isApproved()) {
            log.info("[Engine] {} signal REJECTED — {}", symbol, validation.getReason());
            return;
        }

        // 6. Execute via broker
        PositionDTO position = brokerService.openPosition(signal);

        // 7. Notify (async via Kafka)
        eventPublisher.publishTradeOpenNotification(position);

        log.info("[Engine] ✅ OPENED {} {} x{} @ ₹{} [{}] — {}",
                signal.getSignal(), symbol, signal.getQuantity(),
                String.format("%.2f", position.getEntryPrice()),
                position.getPositionId(),
                signal.getSignalReason());
    }

    // ── Daily summary console print ───────────────────────────

    private void printDailySummary(DailySummaryDTO s) {
        String sep = repeat("=", 60);
        System.out.println("\n" + sep);
        System.out.println("  DAILY SUMMARY — " + s.getDate());
        System.out.println(sep);
        System.out.printf("  Total P&L   : ₹%+.2f%n", s.getTotalPnl());
        System.out.printf("  Trades      : %d  (W:%d / L:%d)%n", s.getTrades(), s.getWins(), s.getLosses());
        System.out.printf("  Win Rate    : %.1f%%%n", s.getWinRate());
        System.out.printf("  Best Trade  : ₹%+.2f%n", s.getBestTrade());
        System.out.printf("  Worst Trade : ₹%+.2f%n", s.getWorstTrade());
        System.out.printf("  Circuit     : %s%n", s.isCircuitTripped() ? "TRIPPED ❌" : "CLEAR ✅");
        if (sheetsService.isConnected())
            System.out.printf("  Database    : %s%n", sheetsService.getSheetUrl());
        System.out.println(sep + "\n");
    }
}
