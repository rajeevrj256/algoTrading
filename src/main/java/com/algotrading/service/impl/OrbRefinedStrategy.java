package com.algotrading.service.impl;

import com.algotrading.dto.CandleDTO;
import com.algotrading.dto.TradeSignalDTO;
import com.algotrading.enums.SignalType;
import com.algotrading.enums.StrategyType;
import com.algotrading.service.TradingStrategy;
import com.algotrading.util.Indicator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

/**
 * OrbRefinedStrategy — Opening Range Breakout, but only in the gap/bias direction.
 *
 * Fixes the old ORB (which broke both ways, RR < filter, no bias): trades only
 * 09:30–10:30, only when the breakout agrees with the gap vs previous close, with
 * a volume confirm and a risk-based 2R target so it clears charges.
 */
@Slf4j
@Component
public class OrbRefinedStrategy implements TradingStrategy {

    private static final LocalTime ORB_START = LocalTime.of(9, 15);
    private static final LocalTime ORB_END   = LocalTime.of(9, 30);
    private static final LocalTime WIN_START = LocalTime.of(9, 30);
    private static final LocalTime WIN_END   = LocalTime.of(10, 30);
    private static final double TARGET_R = 2.0;
    private static final double MIN_MOVE_PCT = 0.4;

    @Override public StrategyType getType() { return StrategyType.ORB_REFINED; }
    @Override public int minCandles()       { return 5; }

    @Override
    public Optional<TradeSignalDTO> generate(String symbol, List<CandleDTO> candles) {
        int n = candles.size();
        if (n < minCandles()) return Optional.empty();

        CandleDTO last = candles.get(n - 1);
        CandleDTO prev = candles.get(n - 2);
        if (last.getTimestamp() == null) return Optional.empty();
        LocalTime t = last.getTimestamp().toLocalTime();
        if (t.isBefore(WIN_START) || t.isAfter(WIN_END)) return Optional.empty();

        LocalDate today = last.getTimestamp().toLocalDate();
        double orbH = Double.MIN_VALUE, orbL = Double.MAX_VALUE;
        boolean haveOrb = false;
        Double prevClose = null, todayOpen = null;
        for (CandleDTO c : candles) {
            if (c.getTimestamp() == null) continue;
            LocalDate d = c.getTimestamp().toLocalDate();
            LocalTime ct = c.getTimestamp().toLocalTime();
            if (d.isBefore(today)) {
                prevClose = c.getClose();                 // last pre-today candle wins
            } else if (d.equals(today)) {
                if (todayOpen == null) todayOpen = c.getOpen();
                if (!ct.isBefore(ORB_START) && !ct.isAfter(ORB_END)) {
                    orbH = Math.max(orbH, c.getHigh());
                    orbL = Math.min(orbL, c.getLow());
                    haveOrb = true;
                }
            }
        }
        if (!haveOrb || prevClose == null || todayOpen == null) return Optional.empty();

        double avgVol = Indicator.rollingAvgVolume(candles, 20);
        double volRatio = avgVol > 0 ? last.getVolume() / avgVol : 0;
        if (volRatio < 1.5) return Optional.empty();

        double[] closes = Indicator.closes(candles);
        double rsi = Indicator.rsi(closes, 14)[n - 1];
        boolean biasLong  = todayOpen >= prevClose;       // gap up / flat-up
        boolean biasShort = todayOpen <= prevClose;
        double price = last.getClose();

        // ── LONG breakout in gap-up bias ──
        if (biasLong && prev.getClose() <= orbH && price > orbH) {
            double stop = Indicator.round2(orbL);
            double risk = price - stop;
            if (risk <= 0) return Optional.empty();
            double target = Indicator.round2(price + TARGET_R * risk);
            if ((target - price) / price * 100.0 < MIN_MOVE_PCT) return Optional.empty();
            return Optional.of(build(symbol, SignalType.BUY, price, stop, target, rsi, volRatio, orbH, orbL,
                    String.format("ORB breakout (gap-up bias) > ₹%.2f | Vol %.1fx", orbH, volRatio)));
        }

        // ── SHORT breakdown in gap-down bias ──
        if (biasShort && prev.getClose() >= orbL && price < orbL) {
            double stop = Indicator.round2(orbH);
            double risk = stop - price;
            if (risk <= 0) return Optional.empty();
            double target = Indicator.round2(price - TARGET_R * risk);
            if ((price - target) / price * 100.0 < MIN_MOVE_PCT) return Optional.empty();
            return Optional.of(build(symbol, SignalType.SELL, price, stop, target, rsi, volRatio, orbH, orbL,
                    String.format("ORB breakdown (gap-down bias) < ₹%.2f | Vol %.1fx", orbL, volRatio)));
        }

        return Optional.empty();
    }

    private TradeSignalDTO build(String symbol, SignalType side, double entry, double stop, double target,
                                 double rsi, double volRatio, double orbH, double orbL, String reason) {
        return TradeSignalDTO.builder()
                .symbol(symbol).signal(side).strategy(StrategyType.ORB_REFINED)
                .entryPrice(entry).stopLoss(stop).target(target)
                .quantity(qty(entry, stop)).confidence(0.70).rrRatio(rr(entry, stop, target))
                .signalReason(reason)
                .whyFull(String.format(
                        "1) Breakout of the 09:15–09:30 opening range in the same direction as the gap vs prev close. " +
                        "2) Volume %.1fx confirms participation. 3) Stop at the opposite range edge (₹%.2f). " +
                        "4) Target = 2R, ≥0.4%% move so it clears charges. Morning window only (09:30–10:30).",
                        volRatio, side == SignalType.BUY ? orbL : orbH))
                .indRsi(String.format("RSI(14) = %.1f", rsi))
                .indVolRatio(String.format("Volume %.2fx avg", volRatio))
                .indExtra(String.format("ORB H=₹%.2f L=₹%.2f", orbH, orbL))
                .build();
    }

    private int    qty(double e, double s) { double r = 100000 * 0.002; double d = Math.abs(e - s); return d > 0 ? Math.max(1, (int)(r / d)) : 1; }
    private double rr(double e, double s, double t) { double r = Math.abs(e - s); return r > 0 ? Indicator.round2(Math.abs(t - e) / r) : 0; }
}
