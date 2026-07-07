package com.algotrading.service.impl;

import com.algotrading.dto.CandleDTO;
import com.algotrading.dto.TradeSignalDTO;
import com.algotrading.enums.SignalType;
import com.algotrading.enums.StrategyType;
import com.algotrading.service.TradingStrategy;
import com.algotrading.util.Indicator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

/**
 * EmaPullbackStrategy — salvages the gross-positive EMA edge by fixing the entry.
 *
 * The old EMA_CROSS entered ON the crossover — late and whippy. This enters on a
 * pullback inside an already-established, stacked-EMA trend, which avoids the
 * chop around the cross.
 *
 * BUY  : EMA9 > EMA21 > EMA50 (stacked up) AND price pulled back to ~EMA21 then
 *        reclaimed EMA9 with a bullish candle. Stop below swing. Target 2.5R.
 */
@Slf4j
@Component
public class EmaPullbackStrategy implements TradingStrategy {

    private static final LocalTime WIN_START = LocalTime.of(9, 30);
    private static final LocalTime WIN_END   = LocalTime.of(14, 30);
    private static final double TARGET_R = 2.5;
    private static final double MIN_MOVE_PCT = 0.4;

    /**
     * Minimum EMA9↔EMA50 spread (% of price) for the stack to count as a REAL trend.
     * A technically-stacked but paper-thin spread (e.g. 0.06% on FINNIFTY, 2026-07-07)
     * is flat chop wearing a trend costume — both F&O losses that day entered on it.
     */
    private static final double MIN_STACK_SPREAD_PCT = 0.15;

    @Override public StrategyType getType() { return StrategyType.EMA_PULLBACK; }
    @Override public int minCandles()       { return 55; }

    @Override
    public Optional<TradeSignalDTO> generate(String symbol, List<CandleDTO> candles) {
        int n = candles.size();
        if (n < minCandles()) return Optional.empty();

        CandleDTO last = candles.get(n - 1);
        CandleDTO prev = candles.get(n - 2);
        if (last.getTimestamp() == null) return Optional.empty();
        LocalTime t = last.getTimestamp().toLocalTime();
        if (t.isBefore(WIN_START) || t.isAfter(WIN_END)) return Optional.empty();

        double[] closes = Indicator.closes(candles);
        double[] ema9   = Indicator.ema(closes, 9);
        double[] ema21  = Indicator.ema(closes, 21);
        double[] ema50  = Indicator.ema(closes, 50);
        double[] atrArr = Indicator.atr(candles, 14);
        double[] rsiArr = Indicator.rsi(closes, 14);

        int i = n - 1;
        double price = closes[i], atr = atrArr[i], rsi = rsiArr[i];
        double e9 = ema9[i], e21 = ema21[i], e50 = ema50[i];
        if (atr <= 0) return Optional.empty();

        boolean bounceUp = last.getClose() > last.getOpen() && last.getClose() > e9;
        boolean bounceDn = last.getClose() < last.getOpen() && last.getClose() < e9;

        // Trend-quality gate: the stack must have real separation, not a flat-market technicality.
        double stackSpreadPct = Math.abs(e9 - e50) / price * 100.0;
        if (stackSpreadPct < MIN_STACK_SPREAD_PCT) return Optional.empty();

        // ── LONG — stacked up, pullback to EMA21 holds ──
        if (e9 > e21 && e21 > e50 && price > e50 && rsi < 70) {
            boolean pulledToEma21 = Math.min(last.getLow(), prev.getLow()) <= e21 * 1.002;
            if (pulledToEma21 && bounceUp) {
                double stop = Indicator.round2(Math.min(Math.min(last.getLow(), prev.getLow()), e21) - 0.1 * atr);
                double risk = price - stop;
                if (risk <= 0) return Optional.empty();
                double target = Indicator.round2(price + TARGET_R * risk);
                if ((target - price) / price * 100.0 < MIN_MOVE_PCT) return Optional.empty();
                return Optional.of(build(symbol, SignalType.BUY, price, stop, target, rsi, e9, e21, e50,
                        String.format("EMA pullback long | stacked up | RSI %.1f", rsi)));
            }
        }

        // ── SHORT — stacked down, pullback to EMA21 fails ──
        if (e9 < e21 && e21 < e50 && price < e50 && rsi > 30) {
            boolean ralliedToEma21 = Math.max(last.getHigh(), prev.getHigh()) >= e21 * 0.998;
            if (ralliedToEma21 && bounceDn) {
                double stop = Indicator.round2(Math.max(Math.max(last.getHigh(), prev.getHigh()), e21) + 0.1 * atr);
                double risk = stop - price;
                if (risk <= 0) return Optional.empty();
                double target = Indicator.round2(price - TARGET_R * risk);
                if ((price - target) / price * 100.0 < MIN_MOVE_PCT) return Optional.empty();
                return Optional.of(build(symbol, SignalType.SELL, price, stop, target, rsi, e9, e21, e50,
                        String.format("EMA pullback short | stacked down | RSI %.1f", rsi)));
            }
        }

        return Optional.empty();
    }

    private TradeSignalDTO build(String symbol, SignalType side, double entry, double stop, double target,
                                 double rsi, double e9, double e21, double e50, String reason) {
        return TradeSignalDTO.builder()
                .symbol(symbol).signal(side).strategy(StrategyType.EMA_PULLBACK)
                .entryPrice(entry).stopLoss(stop).target(target)
                .quantity(qty(entry, stop)).confidence(0.70).rrRatio(rr(entry, stop, target))
                .signalReason(reason)
                .whyFull(String.format(
                        "1) Established %s trend: EMA9/21/50 stacked in order. " +
                        "2) Price pulled back to EMA21 and the candle reclaimed EMA9 — continuation, not a fresh cross. " +
                        "3) Stop beyond the pullback swing at ₹%.2f. 4) Target 2.5R, ≥0.4%% move (clears charges).",
                        side == SignalType.BUY ? "up" : "down", stop))
                .indRsi(String.format("RSI(14) = %.1f", rsi))
                .indEmaGap(String.format("EMA9=₹%.2f EMA21=₹%.2f EMA50=₹%.2f", e9, e21, e50))
                .build();
    }

    private int    qty(double e, double s) { double r = 100000 * 0.002; double d = Math.abs(e - s); return d > 0 ? Math.max(1, (int)(r / d)) : 1; }
    private double rr(double e, double s, double t) { double r = Math.abs(e - s); return r > 0 ? Indicator.round2(Math.abs(t - e) / r) : 0; }
}
