package com.algotrading.service.impl;

import com.algotrading.dto.PositionDTO;
import com.algotrading.dto.TradeSignalDTO;
import com.algotrading.enums.PositionStatus;
import com.algotrading.enums.SignalType;
import com.algotrading.model.Order;
import com.algotrading.service.BrokerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

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
public class PaperBrokerServiceImpl implements BrokerService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Value("${broker.slippage-pct:0.02}")
    private double slippagePct;

    private final Map<String, PositionDTO> openPositions   = new ConcurrentHashMap<>();
    private final List<PositionDTO>        closedPositions = Collections.synchronizedList(new ArrayList<>());
    private final List<Order>              orders          = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger            posCounter      = new AtomicInteger(1);
    private final AtomicInteger            ordCounter      = new AtomicInteger(1);

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
                .entryPrice(filled).stopLoss(signal.getStopLoss()).target(signal.getTarget())
                .quantity(signal.getQuantity()).signal(signal.getSignal()).strategy(signal.getStrategy())
                .entryTime(now).status(PositionStatus.OPEN)
                .signalReason(signal.getSignalReason()).whyFull(signal.getWhyFull())
                .indRsi(signal.getIndRsi()).indEmaGap(signal.getIndEmaGap())
                .indVwapDev(signal.getIndVwapDev()).indVolRatio(signal.getIndVolRatio())
                .indAtr(signal.getIndAtr()).indExtra(signal.getIndExtra())
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
        double pnl = pos.getSignal() == SignalType.BUY
                ? (exitPrice - pos.getEntryPrice()) * pos.getQuantity()
                : (pos.getEntryPrice() - exitPrice) * pos.getQuantity();
        double pnlPct = pnl / (pos.getEntryPrice() * pos.getQuantity()) * 100;

        pos.setExitPrice(r2(exitPrice));
        pos.setExitTime(LocalDateTime.now(IST));
        pos.setPnl(r2(pnl));
        pos.setPnlPct(r2(pnlPct));
        pos.setStatus(PositionStatus.CLOSED);
        pos.setExitReason(exitReason);

        closedPositions.add(pos);
        log.info("[Broker] CLOSED {} {} @ ₹{} P&L=₹{} | {}",
                pos.getSymbol(), pos.getSignal(), exitPrice, r2(pnl), exitReason);
        return pos;
    }

    // ── checkExits ────────────────────────────────────────────

    @Override
    public List<PositionDTO> checkExits(String symbol, double currentPrice) {
        List<String> toClose = openPositions.values().stream()
                .filter(p -> p.getSymbol().equals(symbol))
                .filter(p -> shouldExit(p, currentPrice))
                .map(PositionDTO::getPositionId)
                .collect(Collectors.toList());

        List<PositionDTO> closed = new ArrayList<>();
        for (String id : toClose) {
            PositionDTO p = openPositions.get(id);
            if (p == null) continue;
            String reason = exitReason(p, currentPrice);
            PositionDTO c = closePosition(id, currentPrice, reason);
            if (c != null) closed.add(c);
        }
        return closed;
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

    private boolean shouldExit(PositionDTO p, double price) {
        if (p.getSignal() == SignalType.BUY)
            return price >= p.getTarget() || price <= p.getStopLoss();
        else
            return price <= p.getTarget() || price >= p.getStopLoss();
    }

    private String exitReason(PositionDTO p, double price) {
        if (p.getSignal() == SignalType.BUY) {
            if (price >= p.getTarget())   return String.format("TARGET HIT @ ₹%.2f", price);
            if (price <= p.getStopLoss()) return String.format("STOP LOSS @ ₹%.2f", price);
        } else {
            if (price <= p.getTarget())   return String.format("TARGET HIT @ ₹%.2f", price);
            if (price >= p.getStopLoss()) return String.format("STOP LOSS @ ₹%.2f", price);
        }
        return String.format("MANUAL EXIT @ ₹%.2f", price);
    }

    private double r2(double v) { return Math.round(v * 100.0) / 100.0; }
}
