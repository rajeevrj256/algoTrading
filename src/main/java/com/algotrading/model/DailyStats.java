package com.algotrading.model;

import lombok.Getter;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * DailyStats — thread-safe in-memory holder for today's trading statistics.
 * Uses AtomicReference/AtomicInteger so concurrent scan threads don't corrupt state.
 * Reset each morning via RiskService.resetDay().
 */
@Getter
public class DailyStats {

    private volatile String date;

    private final AtomicReference<Double> totalPnl      = new AtomicReference<>(0.0);
    private final AtomicInteger           trades         = new AtomicInteger(0);
    private final AtomicInteger           wins           = new AtomicInteger(0);
    private final AtomicInteger           losses         = new AtomicInteger(0);
    private final AtomicReference<Double> peakPnl        = new AtomicReference<>(0.0);
    private final AtomicReference<Double> bestTrade      = new AtomicReference<>(0.0);
    private final AtomicReference<Double> worstTrade     = new AtomicReference<>(0.0);
    private final AtomicBoolean           circuitTripped = new AtomicBoolean(false);

    public DailyStats(String date) {
        this.date = date;
    }

    /** Record a closed trade's P&L — thread-safe. */
    public void record(double pnl) {
        totalPnl.updateAndGet(v -> v + pnl);
        trades.incrementAndGet();
        if (pnl > 0) wins.incrementAndGet();
        else         losses.incrementAndGet();
        peakPnl.updateAndGet(v  -> Math.max(v, totalPnl.get()));
        bestTrade.updateAndGet(v -> Math.max(v, pnl));
        worstTrade.updateAndGet(v -> Math.min(v, pnl));
    }

    public double getWinRate() {
        int t = trades.get();
        return t > 0 ? wins.get() * 100.0 / t : 0.0;
    }

    public double getAvgPnl() {
        int t = trades.get();
        return t > 0 ? totalPnl.get() / t : 0.0;
    }

    /** Reset all fields for a new trading day. */
    public void reset(String newDate) {
        this.date = newDate;
        totalPnl.set(0.0);
        trades.set(0);
        wins.set(0);
        losses.set(0);
        peakPnl.set(0.0);
        bestTrade.set(0.0);
        worstTrade.set(0.0);
        circuitTripped.set(false);
    }
}
