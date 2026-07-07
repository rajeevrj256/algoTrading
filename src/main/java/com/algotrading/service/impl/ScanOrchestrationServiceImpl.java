package com.algotrading.service.impl;

import com.algotrading.dto.*;
import com.algotrading.enums.AlertType;
import com.algotrading.enums.Segment;
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

    private final DataFeedService       dataFeedService;
    private final StrategyService       strategyService;
    private final RiskService           riskService;
    private final BrokerService         brokerService;
    private final IntradaySymbolService intradaySymbolService;
    private final SheetsService         sheetsService;       // kept for printDailySummary() only
    private final TradingEventPublisher eventPublisher;
    private final OptionChainService    optionChainService;

    @org.springframework.beans.factory.annotation.Value("${app.candle-count:120}")
    private int candleCount;

    /** Engine on/off switches — both on = run equity + F&O passes each scan. */
    @org.springframework.beans.factory.annotation.Value("${trading.equity.enabled:true}")
    private boolean equityEngineEnabled;

    @org.springframework.beans.factory.annotation.Value("${trading.fno.enabled:true}")
    private boolean fnoEngineEnabled;

    /** Re-entry chop guard: after ANY stop-loss exit, block the symbol this long. */
    @org.springframework.beans.factory.annotation.Value("${risk.stop-cooldown-min:20}")
    private long stopCooldownMin;

    private final Map<String, LocalDateTime> stopCooldowns = new ConcurrentHashMap<>();

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

        // Two independent engine passes each scan (toggled in application.yml).
        boolean equityOn = equityEngineEnabled;
        boolean fnoOn = fnoEngineEnabled && optionChainService.isFnoEnabled();

        int scanned = 0;
        if (equityOn) {
            scanned += runEnginePass(intradaySymbolService.getSymbolsForScan(), Segment.EQUITY);
        }
        if (fnoOn) {
            scanned += runEnginePass(intradaySymbolService.getFnoUnderlyings(), Segment.FNO);
        }
        if (!equityOn && !fnoOn) {
            log.warn("[Engine] Scan skipped — both engines disabled (trading.equity/fno.enabled)");
        } else if (scanned == 0) {
            log.warn("[Engine] Scan skipped — no eligible symbols for the enabled engine(s)");
        }

        // Refresh open positions tab (async via Kafka)
        eventPublisher.publishOpenPositionsUpdate(brokerService.getOpenPositions());
        log.info("[Engine] ===== SCAN END =====");
    }

    /** Run one engine pass over its symbols with its strategy segment. Returns count scanned. */
    private int runEnginePass(List<String> symbols, Segment segment) {
        if (symbols == null || symbols.isEmpty()) {
            log.info("[Engine] {} pass — no symbols", segment);
            return 0;
        }
        log.info("[Engine] {} pass — {} symbol(s)", segment, symbols.size());
        for (String symbol : symbols) {
            try {
                processSymbol(symbol, segment);
            } catch (Exception e) {
                log.error("[Engine] Unexpected error processing {} [{}]: {}", symbol, segment, e.getMessage(), e);
            }
        }
        return symbols.size();
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
                double price = pos.isOption()
                        ? optionChainService.getOptionLtp(pos).orElse(pos.getEntryPrice())
                        : dataFeedService.getLastPrice(pos.getSymbol()).orElse(pos.getEntryPrice());

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

    private void processSymbol(String symbol, Segment segment) {
        // 1. Check if already in a position for this symbol (options match on their underlying).
        boolean alreadyOpen = brokerService.getOpenPositions().stream()
                .anyMatch(p -> symbol.equals(p.getSymbol()) || symbol.equals(p.getUnderlying()));

        // 2. Check exits only for symbols that are currently open.
        if (alreadyOpen) {
            checkOpenPositionExits(symbol);
        }

        // 3. Skip signal scan if already in a position for this symbol
        if (alreadyOpen) {
            log.debug("[Engine] {} already has an open position — skipping signal scan", symbol);
            return;
        }

        LocalDateTime cooldownUntil = activeStopCooldown(symbol);
        if (cooldownUntil != null) {
            log.info("[Engine] {} skipped — stop-loss cooldown active until {}", symbol, cooldownUntil.toLocalTime());
            return;
        }

        // 4. Fetch candles
        List<CandleDTO> candles = dataFeedService.getCandles(symbol, candleCount);
        List<CandleDTO> strategyCandles = completedCandlesOnly(symbol, candles);
        if (strategyCandles.size() < 30) {
            log.debug("[Engine] {} — insufficient completed candle data ({})", symbol, strategyCandles.size());
            return;
        }

        // 5. Run this segment's strategies — confluence-aware (conflicting signals cancel)
        Optional<TradeSignalDTO> signalOpt = strategyService.runStrategies(symbol, strategyCandles, segment);
        if (!signalOpt.isPresent()) {
            log.debug("[Engine] {} [{}] — no signal from any strategy", symbol, segment);
            return;
        }
        TradeSignalDTO signal = signalOpt.get();

        // 5b. F&O pass: index signals become option-chain orders (BUY→CE / SELL→PE)
        if (segment == Segment.FNO && optionChainService.isOptionUnderlying(symbol)) {
            Optional<TradeSignalDTO> optionSignal = optionChainService.toOptionSignal(signal);
            if (!optionSignal.isPresent()) {
                log.info("[Engine] {} index signal dropped — no tradable option contract", symbol);
                return;
            }
            signal = optionSignal.get();
        }

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
                signal.getSignal(), signal.getSymbol(), signal.getQuantity(),
                String.format("%.2f", position.getEntryPrice()),
                position.getPositionId(),
                signal.getSignalReason());
    }

    private void checkOpenPositionExits(String symbol) {
        List<PositionDTO> forSymbol = new ArrayList<>();
        for (PositionDTO p : brokerService.getOpenPositions()) {
            if (symbol.equals(p.getSymbol()) || symbol.equals(p.getUnderlying())) {
                forSymbol.add(p);
            }
        }
        if (forSymbol.isEmpty()) return;

        Optional<Double> equityPriceCache = Optional.empty();
        boolean equityPriceFetched = false;

        for (PositionDTO p : forSymbol) {
            if (p.isOption()) {
                checkOptionPositionExit(p);
                continue;
            }
            if (!equityPriceFetched) {
                equityPriceCache = dataFeedService.getLastPrice(p.getSymbol());
                equityPriceFetched = true;
            }
            if (!equityPriceCache.isPresent()) continue;
            List<PositionDTO> closed = brokerService.checkExits(p.getSymbol(), equityPriceCache.get());
            for (PositionDTO c : closed) {
                handleClosedPosition(c.getSymbol(), c);
            }
        }
    }

    private void checkOptionPositionExit(PositionDTO p) {
        Optional<Double> underlyingPrice = dataFeedService.getLastPrice(p.getUnderlying());
        Optional<Double> optionLtp = optionChainService.getOptionLtp(p);
        if (!underlyingPrice.isPresent() || !optionLtp.isPresent()) {
            log.debug("[Engine] {} option exit check skipped — missing quote (underlying={}, ltp={})",
                    p.getSymbol(), underlyingPrice.isPresent(), optionLtp.isPresent());
            return;
        }
        PositionDTO closed = brokerService.checkOptionExit(p.getPositionId(), underlyingPrice.get(), optionLtp.get());
        if (closed != null) {
            handleClosedPosition(closed.getSymbol(), closed);
        }
    }

    private void handleClosedPosition(String symbol, PositionDTO closedPosition) {
        riskService.recordTrade(closedPosition.getPnl());
        registerStopCooldown(closedPosition);
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

    /**
     * Re-entry chop guard: after ANY stop-loss exit, block fresh entries on that
     * symbol for a while. Losing setups tend to re-fire immediately in the same
     * chop that stopped them out — the pause is cheap insurance.
     * Options key the cooldown on the UNDERLYING so the index can't re-trigger
     * via a different strike.
     */
    private void registerStopCooldown(PositionDTO closedPosition) {
        if (stopCooldownMin <= 0) return;
        String exitReason = closedPosition.getExitReason();
        if (exitReason == null || !(exitReason.startsWith("STOP LOSS") || exitReason.startsWith("PREMIUM STOP"))) {
            return;
        }

        String key = closedPosition.isOption() && closedPosition.getUnderlying() != null
                ? closedPosition.getUnderlying()
                : closedPosition.getSymbol();
        LocalDateTime cooldownUntil = LocalDateTime.now(IST).plusMinutes(stopCooldownMin);
        stopCooldowns.put(key, cooldownUntil);
        log.info("[Engine] {} cooldown armed until {} after stop-loss exit", key, cooldownUntil.toLocalTime());
    }

    private LocalDateTime activeStopCooldown(String symbol) {
        LocalDateTime cooldownUntil = stopCooldowns.get(symbol);
        if (cooldownUntil == null) {
            return null;
        }
        if (!cooldownUntil.isAfter(LocalDateTime.now(IST))) {
            stopCooldowns.remove(symbol);
            return null;
        }
        return cooldownUntil;
    }
}
