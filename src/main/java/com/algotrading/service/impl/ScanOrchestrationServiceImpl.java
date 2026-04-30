package com.algotrading.service.impl;

import com.algotrading.dto.*;
import com.algotrading.enums.AlertType;
import com.algotrading.enums.StrategyType;
import com.algotrading.event.TradingEventPublisher;
import com.algotrading.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
    private static final int STRATEGY_CANDLE_INTERVAL_MIN = 5;
    private static final long VWAP_STOP_COOLDOWN_MIN = 30;

    private final DataFeedService       dataFeedService;
    private final StrategyService       strategyService;
    private final RiskService           riskService;
    private final BrokerService         brokerService;
    private final IntradaySymbolService intradaySymbolService;
    private final SheetsService         sheetsService;       // kept for printDailySummary() only
    private final TradingEventPublisher eventPublisher;

    @org.springframework.beans.factory.annotation.Value("${app.candle-count:120}")
    private int candleCount;

    private final Map<String, LocalDateTime> vwapStopCooldowns = new ConcurrentHashMap<>();

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

        List<String> symbols = intradaySymbolService.getSymbolsForScan();
        if (symbols.isEmpty()) {
            log.warn("[Engine] Scan skipped — no eligible symbols available");
            return;
        }

        for (String symbol : symbols) {
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

    @Override
    public void runExitChecks() {
        List<PositionDTO> openPositions = brokerService.getOpenPositions();
        if (openPositions.isEmpty()) {
            log.debug("[Engine] Exit check skipped — no open positions");
            return;
        }

        Set<String> openSymbols = new LinkedHashSet<>();
        for (PositionDTO position : openPositions) {
            openSymbols.add(position.getSymbol());
        }

        log.debug("[Engine] Exit check start — {} open positions across {} symbols",
                openPositions.size(), openSymbols.size());

        for (String symbol : openSymbols) {
            checkOpenPositionExits(symbol);
        }
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
        // 1. Check if already in a position for this symbol.
        boolean alreadyOpen = brokerService.getOpenPositions().stream()
                .anyMatch(p -> symbol.equals(p.getSymbol()));

        // 2. Check exits only for symbols that are currently open.
        if (alreadyOpen) {
            checkOpenPositionExits(symbol);
        }

        // 3. Skip signal scan if already in a position for this symbol
        if (alreadyOpen) {
            log.debug("[Engine] {} already has an open position — skipping signal scan", symbol);
            return;
        }

        LocalDateTime cooldownUntil = activeVwapCooldown(symbol);
        if (cooldownUntil != null) {
            log.info("[Engine] {} skipped — VWAP_MR cooldown active until {}", symbol, cooldownUntil.toLocalTime());
            return;
        }

        // 4. Fetch candles
        List<CandleDTO> candles = dataFeedService.getCandles(symbol, candleCount);
        List<CandleDTO> strategyCandles = completedCandlesOnly(symbol, candles);
        if (strategyCandles.size() < 30) {
            log.debug("[Engine] {} — insufficient completed candle data ({})", symbol, strategyCandles.size());
            return;
        }

        // 5. Run all strategies — first signal wins
        Optional<TradeSignalDTO> signalOpt = strategyService.runStrategies(symbol, strategyCandles);
        if (!signalOpt.isPresent()) {
            log.debug("[Engine] {} — no signal from any strategy", symbol);
            return;
        }
        TradeSignalDTO signal = signalOpt.get();

        // 6. Validate signal with risk service
        RiskValidationDTO validation = riskService.validateSignal(signal);
        if (!validation.isApproved()) {
            log.info("[Engine] {} signal REJECTED — {}", symbol, validation.getReason());
            return;
        }

        // 7. Execute via broker
        PositionDTO position = brokerService.openPosition(signal);

        // 8. Notify (async via Kafka)
        eventPublisher.publishTradeOpenNotification(position);
        eventPublisher.publishOpenPositionsUpdate(brokerService.getOpenPositions());

        log.info("[Engine] ✅ OPENED {} {} x{} @ ₹{} [{}] — {}",
                signal.getSignal(), symbol, signal.getQuantity(),
                String.format("%.2f", position.getEntryPrice()),
                position.getPositionId(),
                signal.getSignalReason());
    }

    private void checkOpenPositionExits(String symbol) {
        Optional<Double> lastPrice = dataFeedService.getLastPrice(symbol);
        if (!lastPrice.isPresent()) {
            return;
        }

        List<PositionDTO> closed = brokerService.checkExits(symbol, lastPrice.get());
        for (PositionDTO c : closed) {
            handleClosedPosition(symbol, c);
        }
    }

    private void handleClosedPosition(String symbol, PositionDTO closedPosition) {
        riskService.recordTrade(closedPosition.getPnl());
        registerVwapCooldown(closedPosition);
        eventPublisher.publishTradeLog(closedPosition);
        eventPublisher.publishTradeCloseNotification(closedPosition);
        eventPublisher.publishOpenPositionsUpdate(brokerService.getOpenPositions());
        log.info("[Engine] Exit — {} P&L=₹{} | {}", symbol,
                String.format("%.2f", closedPosition.getPnl()), closedPosition.getExitReason());
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

    private List<CandleDTO> completedCandlesOnly(String symbol, List<CandleDTO> candles) {
        if (candles.isEmpty()) {
            return candles;
        }

        CandleDTO last = candles.get(candles.size() - 1);
        if (last.getTimestamp() == null) {
            return candles;
        }

        LocalDateTime candleClosesAt = last.getTimestamp().plusMinutes(STRATEGY_CANDLE_INTERVAL_MIN);
        if (!candleClosesAt.isAfter(LocalDateTime.now(IST))) {
            return candles;
        }

        if (candles.size() == 1) {
            return Collections.emptyList();
        }

        log.debug("[Engine] {} dropping in-progress candle @ {}", symbol, last.getTimestamp());
        return new ArrayList<>(candles.subList(0, candles.size() - 1));
    }

    private void registerVwapCooldown(PositionDTO closedPosition) {
        if (closedPosition.getStrategy() == null || closedPosition.getStrategy() != StrategyType.VWAP_MR) {
            return;
        }
        String exitReason = closedPosition.getExitReason();
        if (exitReason == null || !exitReason.startsWith("STOP LOSS")) {
            return;
        }

        LocalDateTime cooldownUntil = LocalDateTime.now(IST).plusMinutes(VWAP_STOP_COOLDOWN_MIN);
        vwapStopCooldowns.put(closedPosition.getSymbol(), cooldownUntil);
        log.info("[Engine] {} cooldown armed until {} after VWAP_MR stop loss",
                closedPosition.getSymbol(), cooldownUntil.toLocalTime());
    }

    private LocalDateTime activeVwapCooldown(String symbol) {
        LocalDateTime cooldownUntil = vwapStopCooldowns.get(symbol);
        if (cooldownUntil == null) {
            return null;
        }
        if (!cooldownUntil.isAfter(LocalDateTime.now(IST))) {
            vwapStopCooldowns.remove(symbol);
            return null;
        }
        return cooldownUntil;
    }
}
