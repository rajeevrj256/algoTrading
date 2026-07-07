package com.algotrading.service.impl;

import com.algotrading.dto.PositionDTO;
import com.algotrading.dto.TradeSignalDTO;
import com.algotrading.enums.PositionStatus;
import com.algotrading.enums.SignalType;
import com.algotrading.entity.FnoTradeLogEntity;
import com.algotrading.entity.TradeLogEntity;
import com.algotrading.model.Order;
import com.algotrading.model.TradeCharges;
import com.algotrading.service.BrokerService;
import com.algotrading.service.ChargesService;
import com.algotrading.service.SheetsService;
import com.algotrading.repository.FnoTradeLogRepository;
import com.algotrading.repository.TradeLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * PaperBrokerServiceImpl — implements BrokerService.
 *
 * Simulates paper order fills with configurable slippage.
 * All state is in-memory (ConcurrentHashMap for thread safety).
 * No real broker API is called.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaperBrokerServiceImpl implements BrokerService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Value("${broker.slippage-pct:0.02}")
    private double slippagePct;

    @Value("${broker.trailing.enabled:true}")
    private boolean trailingEnabled;

    @Value("${broker.trailing.breakeven-trigger-r:1.0}")
    private double breakevenTriggerR;

    @Value("${broker.trailing.trail-start-r:1.5}")
    private double trailStartR;

    @Value("${broker.trailing.trail-giveback-r:1.0}")
    private double trailGivebackR;

    private final ChargesService chargesService;
    private final SheetsService sheetsService;
    private final TradeLogRepository tradeLogRepository;
    private final FnoTradeLogRepository fnoTradeLogRepository;

    private final Map<String, PositionDTO> openPositions   = new ConcurrentHashMap<>();
    private final List<PositionDTO>        closedPositions = Collections.synchronizedList(new ArrayList<>());
    private final List<Order>              orders          = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger            posCounter      = new AtomicInteger(1);
    private final AtomicInteger            ordCounter      = new AtomicInteger(1);

    @PostConstruct
    public void restoreOpenPositions() {
        try {
            List<PositionDTO> restored = sheetsService.loadOpenPositions();
            int restoredCount = 0;
            int maxCounter = loadPersistedMaxPositionSequence();

            if (maxCounter > 0) {
                posCounter.set(maxCounter + 1);
            }

            for (PositionDTO position : restored) {
                if (position == null || position.getSymbol() == null || position.getSignal() == null) {
                    continue;
                }

                if (position.getPositionId() == null || position.getPositionId().trim().isEmpty()) {
                    position.setPositionId(nextRestoredPositionId());
                }
                if (position.getStatus() == null) {
                    position.setStatus(PositionStatus.OPEN);
                }

                openPositions.put(position.getPositionId(), position);
                restoredCount++;
                maxCounter = Math.max(maxCounter, extractPositionNumber(position.getPositionId()));
            }

            if (maxCounter > 0) {
                posCounter.set(maxCounter + 1);
            }

            if (restoredCount > 0) {
                log.info("[Broker] Restored {} open position(s) from database", restoredCount);
                sheetsService.updateOpenPositions(getOpenPositions());
            }

            restoreTodayHistory(maxCounter);
        } catch (Exception e) {
            log.error("[Broker] Failed to restore open positions: {}", e.getMessage(), e);
        }
    }

    // ── openPosition ──────────────────────────────────────────

    @Override
    public PositionDTO openPosition(TradeSignalDTO signal) {
        double slip       = signal.getEntryPrice() * slippagePct / 100;
        double filled     = signal.getSignal() == SignalType.BUY
                ? r2(signal.getEntryPrice() + slip)
                : r2(signal.getEntryPrice() - slip);

        String posId  = "POS-" + String.format("%05d", posCounter.getAndIncrement());
        String ordId  = "ORD-" + String.format("%05d", ordCounter.getAndIncrement());
        LocalDateTime now = LocalDateTime.now(IST);

        orders.add(Order.builder()
                .orderId(ordId).positionId(posId).symbol(signal.getSymbol())
                .side(signal.getSignal()).quantity(signal.getQuantity())
                .requestedPrice(signal.getEntryPrice()).filledPrice(filled)
                .status("FILLED").createdAt(now).filledAt(now)
                .notes("Paper fill @ " + slippagePct + "% slippage").build());

        PositionDTO pos = PositionDTO.builder()
                .positionId(posId).symbol(signal.getSymbol())
                .entryPrice(filled).stopLoss(signal.getStopLoss())
                .initialStop(signal.getStopLoss()).peakPrice(filled)
                .target(signal.getTarget())
                .quantity(signal.getQuantity()).signal(signal.getSignal()).strategy(signal.getStrategy())
                .entryTime(now).status(PositionStatus.OPEN)
                .signalReason(signal.getSignalReason()).whyFull(signal.getWhyFull())
                .indRsi(signal.getIndRsi()).indEmaGap(signal.getIndEmaGap())
                .indVwapDev(signal.getIndVwapDev()).indVolRatio(signal.getIndVolRatio())
                .indAtr(signal.getIndAtr()).indExtra(signal.getIndExtra())
                .instrumentType(signal.getInstrumentType())
                .underlying(signal.getUnderlying())
                .optionType(signal.getOptionType())
                .strike(signal.getStrike())
                .expiry(signal.getExpiry())
                .lotSize(signal.getLotSize())
                .lots(signal.getLots())
                .underlyingEntry(signal.getUnderlyingEntry())
                .underlyingStop(signal.getUnderlyingStop())
                .underlyingInitialStop(signal.getUnderlyingStop())
                .underlyingTarget(signal.getUnderlyingTarget())
                .underlyingPeak(signal.getUnderlyingEntry())
                .build();

        openPositions.put(posId, pos);
        log.info("[Broker] OPENED {} {} x{} @ ₹{} [{}]",
                signal.getSignal(), signal.getSymbol(), signal.getQuantity(), filled, posId);
        return pos;
    }

    // ── closePosition ─────────────────────────────────────────

    @Override
    public PositionDTO closePosition(String positionId, double exitPrice, String exitReason) {
        PositionDTO pos = openPositions.remove(positionId);
        if (pos == null) {
            log.warn("[Broker] closePosition: {} not found", positionId);
            return null;
        }
        double roundedExitPrice = r2(exitPrice);
        TradeCharges charges = chargesService.calculate(
                pos.getSignal(), pos.getEntryPrice(), roundedExitPrice, pos.getQuantity(),
                pos.getInstrumentType());
        double pnlPct = pos.getEntryPrice() > 0 && pos.getQuantity() > 0
                ? charges.getNetPnl() / (pos.getEntryPrice() * pos.getQuantity()) * 100
                : 0;

        pos.setExitPrice(roundedExitPrice);
        pos.setExitTime(LocalDateTime.now(IST));
        pos.setGrossPnl(r2(charges.getGrossPnl()));
        pos.setCharges(r2(charges.getTotalCharges()));
        pos.setPnl(r2(charges.getNetPnl()));
        pos.setPnlPct(r2(pnlPct));
        pos.setStatus(PositionStatus.CLOSED);
        pos.setExitReason(exitReason);

        closedPositions.add(pos);
        log.info("[Broker] CLOSED {} {} @ ₹{} Gross=₹{} Charges=₹{} Net=₹{} | {}",
                pos.getSymbol(), pos.getSignal(), roundedExitPrice,
                pos.getGrossPnl(), pos.getCharges(), pos.getPnl(), exitReason);
        return pos;
    }

    // ── checkExits ────────────────────────────────────────────

    @Override
    public List<PositionDTO> checkExits(String symbol, double currentPrice) {
        List<PositionDTO> forSymbol = openPositions.values().stream()
                .filter(p -> p.getSymbol().equals(symbol))
                .filter(p -> !p.isOption())   // option exits go through checkOptionExit
                .collect(Collectors.toList());

        List<PositionDTO> closed = new ArrayList<>();
        for (PositionDTO p : forSymbol) {
            applyTrailingStop(p, currentPrice);     // tighten stop BEFORE checking exit
            ExitDecision exit = resolveExit(p, currentPrice);
            if (exit == null) continue;
            PositionDTO c = closePosition(p.getPositionId(), exit.price, exit.reason);
            if (c != null) closed.add(c);
        }
        return closed;
    }

    // ── checkOptionExit (INDEX_OPTION) ────────────────────────

    /**
     * Option exits: decisions on the UNDERLYING index frame (strategy levels),
     * fill at the option LTP (minus slippage — exits are always sells since all
     * option positions are long). Premium stop (delta-mapped + hard stop baked
     * into stopLoss at entry) guards theta/IV bleed even when the index is flat.
     */
    @Override
    public PositionDTO checkOptionExit(String positionId, double underlyingPrice, double optionLtp) {
        PositionDTO p = openPositions.get(positionId);
        if (p == null || !p.isOption()) return null;

        applyUnderlyingTrailingStop(p, underlyingPrice);

        // CE profits when the index rises; PE when it falls.
        boolean longFrame = p.getOptionType() == com.algotrading.enums.OptionType.CE;
        double fill = r2(optionLtp * (1 - slippagePct / 100));   // exit = sell the option

        String reason = null;
        if (longFrame ? underlyingPrice >= p.getUnderlyingTarget()
                      : underlyingPrice <= p.getUnderlyingTarget()) {
            reason = String.format("TARGET HIT (index %.2f ≥|≤ %.2f) @ ₹%.2f",
                    underlyingPrice, p.getUnderlyingTarget(), fill);
        } else if (longFrame ? underlyingPrice <= p.getUnderlyingStop()
                             : underlyingPrice >= p.getUnderlyingStop()) {
            reason = String.format("%s (index %.2f) @ ₹%.2f",
                    underlyingStopLabel(p, longFrame), underlyingPrice, fill);
        } else if (optionLtp <= p.getStopLoss()) {
            reason = String.format("PREMIUM STOP @ ₹%.2f (theta/IV guard, stop ₹%.2f)", fill, p.getStopLoss());
        }

        if (reason == null) return null;
        return closePosition(positionId, fill, reason);
    }

    /** Trailing/breakeven for option positions, computed in the underlying index frame. */
    private void applyUnderlyingTrailingStop(PositionDTO p, double underlyingPrice) {
        if (!trailingEnabled) return;

        double entry = p.getUnderlyingEntry();
        double initStop = p.getUnderlyingInitialStop() != 0 ? p.getUnderlyingInitialStop() : p.getUnderlyingStop();
        double r = Math.abs(entry - initStop);
        if (r <= 0) return;

        boolean longFrame = p.getOptionType() == com.algotrading.enums.OptionType.CE;

        double peak = p.getUnderlyingPeak() != 0 ? p.getUnderlyingPeak() : entry;
        peak = longFrame ? Math.max(peak, underlyingPrice) : Math.min(peak, underlyingPrice);
        p.setUnderlyingPeak(peak);

        double peakR = longFrame ? (peak - entry) / r : (entry - peak) / r;
        if (peakR < breakevenTriggerR) return;

        double lockedR = peakR >= trailStartR ? Math.max(0.0, peakR - trailGivebackR) : 0.0;
        double newStop = longFrame ? entry + lockedR * r : entry - lockedR * r;
        newStop = r2(newStop);

        // Only ever tighten.
        if (longFrame) {
            if (newStop > p.getUnderlyingStop()) p.setUnderlyingStop(newStop);
        } else {
            if (newStop < p.getUnderlyingStop()) p.setUnderlyingStop(newStop);
        }
    }

    private String underlyingStopLabel(PositionDTO p, boolean longFrame) {
        double stop = p.getUnderlyingStop();
        double entry = p.getUnderlyingEntry();
        boolean movedToProfit = longFrame ? stop > entry : stop < entry;
        boolean atBreakeven = Math.abs(stop - entry) < 1e-9;
        return movedToProfit ? "TRAIL STOP" : atBreakeven ? "BREAKEVEN STOP" : "STOP LOSS";
    }

    /**
     * Trailing / breakeven stop. Ratchets the live stop in the favorable direction
     * only — never loosens. Defines 1R from the immutable initialStop:
     *   - peak ≥ breakeven-trigger-R  → stop to entry (winner can't become a loser)
     *   - peak ≥ trail-start-R        → stop locks (peakR − giveback)R of profit
     * Target is left untouched, so winners still run to the full target if they reach it.
     */
    private void applyTrailingStop(PositionDTO p, double price) {
        if (!trailingEnabled) return;

        double entry = p.getEntryPrice();
        double initStop = p.getInitialStop() != 0 ? p.getInitialStop() : p.getStopLoss();
        double r = Math.abs(entry - initStop);
        if (r <= 0) return;

        boolean isLong = p.getSignal() == SignalType.BUY;

        // Track best favorable excursion.
        double peak = p.getPeakPrice() != 0 ? p.getPeakPrice() : entry;
        peak = isLong ? Math.max(peak, price) : Math.min(peak, price);
        p.setPeakPrice(peak);

        double peakR = isLong ? (peak - entry) / r : (entry - peak) / r;
        if (peakR < breakevenTriggerR) return;   // not yet far enough in profit

        double lockedR = peakR >= trailStartR ? Math.max(0.0, peakR - trailGivebackR) : 0.0;
        double newStop = isLong ? entry + lockedR * r : entry - lockedR * r;
        newStop = r2(newStop);

        // Only ever tighten.
        if (isLong) {
            if (newStop > p.getStopLoss()) p.setStopLoss(newStop);
        } else {
            if (newStop < p.getStopLoss()) p.setStopLoss(newStop);
        }
    }

    // ── squareOffAll ──────────────────────────────────────────

    @Override
    public List<PositionDTO> squareOffAll(double currentPrice) {
        List<String> ids = new ArrayList<>(openPositions.keySet());
        log.info("[Broker] EOD square-off: {} open positions", ids.size());
        List<PositionDTO> closed = new ArrayList<>();
        for (String id : ids) {
            PositionDTO c = closePosition(id, currentPrice,
                    String.format("EOD AUTO SQUARE-OFF @ ₹%.2f", currentPrice));
            if (c != null) closed.add(c);
        }
        return closed;
    }

    // ── Queries ───────────────────────────────────────────────

    @Override public List<PositionDTO> getOpenPositions()   { return new ArrayList<>(openPositions.values()); }
    @Override public List<PositionDTO> getClosedPositions() { return new ArrayList<>(closedPositions); }
    @Override public List<Order>       getOrders()          { return new ArrayList<>(orders); }

    @Override
    public Optional<PositionDTO> findPosition(String positionId) {
        PositionDTO p = openPositions.get(positionId);
        if (p != null) return Optional.of(p);
        return closedPositions.stream().filter(c -> c.getPositionId().equals(positionId)).findFirst();
    }

    // ── Helpers ───────────────────────────────────────────────

    private ExitDecision resolveExit(PositionDTO p, double price) {
        if (p.getSignal() == SignalType.BUY) {
            if (price >= p.getTarget()) {
                return new ExitDecision(p.getTarget(), String.format("TARGET HIT @ ₹%.2f", p.getTarget()));
            }
            if (price <= p.getStopLoss()) {
                return new ExitDecision(p.getStopLoss(), stopLabel(p, true));
            }
        } else {
            if (price <= p.getTarget()) {
                return new ExitDecision(p.getTarget(), String.format("TARGET HIT @ ₹%.2f", p.getTarget()));
            }
            if (price >= p.getStopLoss()) {
                return new ExitDecision(p.getStopLoss(), stopLabel(p, false));
            }
        }
        return null;
    }

    /**
     * Distinguish an original stop-loss from a trailed/breakeven stop so the
     * expectancy report and logs show what actually happened.
     */
    private String stopLabel(PositionDTO p, boolean isLong) {
        double stop = p.getStopLoss();
        double entry = p.getEntryPrice();
        boolean movedToProfit = isLong ? stop > entry : stop < entry;
        boolean atBreakeven   = Math.abs(stop - entry) < 1e-9;
        String kind = movedToProfit ? "TRAIL STOP" : atBreakeven ? "BREAKEVEN STOP" : "STOP LOSS";
        return String.format("%s @ ₹%.2f", kind, stop);
    }

    private String nextRestoredPositionId() {
        return "POS-" + String.format("%05d", posCounter.getAndIncrement());
    }

    private int loadPersistedMaxPositionSequence() {
        try {
            // POS-n is a single shared sequence across equity + F&O → max of both tables.
            Integer equityMax = tradeLogRepository.findMaxPositionSequence();
            Integer fnoMax = fnoTradeLogRepository.findMaxPositionSequence();
            return Math.max(equityMax != null ? equityMax : 0, fnoMax != null ? fnoMax : 0);
        } catch (Exception e) {
            log.warn("[Broker] Failed to read historical max position id: {}", e.getMessage());
            return 0;
        }
    }

    private int extractPositionNumber(String positionId) {
        if (positionId == null || !positionId.startsWith("POS-")) {
            return 0;
        }
        try {
            return Integer.parseInt(positionId.substring(4));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void restoreTodayHistory(int currentMaxCounter) {
        List<TradeLogEntity> trades = tradeLogRepository.findByTradeDate(LocalDate.now(IST));
        int maxCounter = currentMaxCounter;

        for (TradeLogEntity trade : trades) {
            String positionId = trade.getPositionId();
            if (positionId == null || positionId.trim().isEmpty()) {
                positionId = "HIST-" + trade.getId();
            }

            closedPositions.add(PositionDTO.builder()
                    .positionId(positionId)
                    .symbol(trade.getSymbol())
                    .entryPrice(trade.getEntryPrice())
                    .exitPrice(trade.getExitPrice())
                    .stopLoss(trade.getStopLoss())
                    .target(trade.getTarget())
                    .quantity(trade.getQuantity())
                    .instrumentType(parseInstrumentType(trade.getInstrumentType()))
                    .underlying(trade.getUnderlying())
                    .optionType(parseOptionType(trade.getOptionType()))
                    .strike(trade.getStrike())
                    .expiry(trade.getExpiry())
                    .lotSize(trade.getLotSize())
                    .lots(trade.getLots())
                    .signal(parseSignal(trade.getSide()))
                    .strategy(parseStrategy(trade.getStrategy()))
                    .entryTime(toDateTime(trade.getTradeDate(), trade.getTradeTime()))
                    .exitTime(trade.getCreatedAt())
                    .grossPnl(r2(trade.getPnl() + trade.getCharges()))
                    .charges(trade.getCharges())
                    .pnl(trade.getPnl())
                    .pnlPct(trade.getPnlPct())
                    .status(PositionStatus.CLOSED)
                    .exitReason(trade.getExitReason())
                    .signalReason(trade.getSignalReason())
                    .whyFull(trade.getWhyFull())
                    .indRsi(trade.getIndRsi())
                    .indEmaGap(trade.getIndEmaGap())
                    .indVwapDev(trade.getIndVwapDev())
                    .indVolRatio(trade.getIndVolRatio())
                    .indAtr(trade.getIndAtr())
                    .indExtra(trade.getIndExtra())
                    .build());

            orders.add(Order.builder()
                    .orderId("ORD-HIST-" + trade.getId())
                    .positionId(positionId)
                    .symbol(trade.getSymbol())
                    .side(parseSignal(trade.getSide()))
                    .quantity(trade.getQuantity())
                    .requestedPrice(trade.getEntryPrice())
                    .filledPrice(trade.getEntryPrice())
                    .status("FILLED")
                    .createdAt(toDateTime(trade.getTradeDate(), trade.getTradeTime()))
                    .filledAt(toDateTime(trade.getTradeDate(), trade.getTradeTime()))
                    .notes("Restored from trade_log")
                    .build());

            maxCounter = Math.max(maxCounter, extractPositionNumber(positionId));
        }

        // F&O closed trades live in fno_trade_log — restore them too so today's summary
        // and the POS-n counter stay whole across a restart.
        List<FnoTradeLogEntity> fnoTrades = fnoTradeLogRepository.findByTradeDate(LocalDate.now(IST));
        for (FnoTradeLogEntity trade : fnoTrades) {
            String positionId = trade.getPositionId();
            if (positionId == null || positionId.trim().isEmpty()) {
                positionId = "HIST-FNO-" + trade.getId();
            }
            closedPositions.add(PositionDTO.builder()
                    .positionId(positionId)
                    .symbol(trade.getSymbol())
                    .entryPrice(trade.getEntryPrice())
                    .exitPrice(trade.getExitPrice())
                    .stopLoss(trade.getStopLoss())
                    .target(trade.getTarget())
                    .quantity(trade.getQuantity())
                    .instrumentType(parseInstrumentType(trade.getInstrumentType()))
                    .underlying(trade.getUnderlying())
                    .optionType(parseOptionType(trade.getOptionType()))
                    .strike(trade.getStrike())
                    .expiry(trade.getExpiry())
                    .lotSize(trade.getLotSize())
                    .lots(trade.getLots())
                    .signal(parseSignal(trade.getSide()))
                    .strategy(parseStrategy(trade.getStrategy()))
                    .entryTime(toDateTime(trade.getTradeDate(), trade.getTradeTime()))
                    .exitTime(trade.getCreatedAt())
                    .grossPnl(r2(trade.getPnl() + trade.getCharges()))
                    .charges(trade.getCharges())
                    .pnl(trade.getPnl())
                    .pnlPct(trade.getPnlPct())
                    .status(PositionStatus.CLOSED)
                    .exitReason(trade.getExitReason())
                    .signalReason(trade.getSignalReason())
                    .whyFull(trade.getWhyFull())
                    .indRsi(trade.getIndRsi())
                    .indEmaGap(trade.getIndEmaGap())
                    .indVwapDev(trade.getIndVwapDev())
                    .indVolRatio(trade.getIndVolRatio())
                    .indAtr(trade.getIndAtr())
                    .indExtra(trade.getIndExtra())
                    .build());
            maxCounter = Math.max(maxCounter, extractPositionNumber(positionId));
        }

        for (PositionDTO openPosition : openPositions.values()) {
            orders.add(Order.builder()
                    .orderId("ORD-OPEN-" + openPosition.getPositionId())
                    .positionId(openPosition.getPositionId())
                    .symbol(openPosition.getSymbol())
                    .side(openPosition.getSignal())
                    .quantity(openPosition.getQuantity())
                    .requestedPrice(openPosition.getEntryPrice())
                    .filledPrice(openPosition.getEntryPrice())
                    .status("FILLED")
                    .createdAt(openPosition.getEntryTime())
                    .filledAt(openPosition.getEntryTime())
                    .notes("Restored from open_position")
                    .build());
            maxCounter = Math.max(maxCounter, extractPositionNumber(openPosition.getPositionId()));
        }

        if (maxCounter > 0) {
            posCounter.set(maxCounter + 1);
        }

        if (!trades.isEmpty()) {
            log.info("[Broker] Restored {} closed trade(s) and {} order(s) for today", trades.size(), orders.size());
        }
    }

    private SignalType parseSignal(String side) {
        if (side == null) return null;
        try {
            return SignalType.valueOf(side);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private com.algotrading.enums.StrategyType parseStrategy(String strategy) {
        if (strategy == null) return null;
        try {
            return com.algotrading.enums.StrategyType.valueOf(strategy);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private com.algotrading.enums.InstrumentType parseInstrumentType(String value) {
        if (value == null) return null;
        try {
            return com.algotrading.enums.InstrumentType.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private com.algotrading.enums.OptionType parseOptionType(String value) {
        if (value == null) return null;
        try {
            return com.algotrading.enums.OptionType.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private LocalDateTime toDateTime(LocalDate date, java.time.LocalTime time) {
        if (date == null) return null;
        return date.atTime(time != null ? time : java.time.LocalTime.MIN);
    }

    private double r2(double v) { return Math.round(v * 100.0) / 100.0; }

    private static final class ExitDecision {
        private final double price;
        private final String reason;

        private ExitDecision(double price, String reason) {
            this.price = price;
            this.reason = reason;
        }
    }
}
