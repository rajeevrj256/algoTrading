package com.algotrading.service.impl;

import com.algotrading.dto.CandleDTO;
import com.algotrading.dto.TradeSignalDTO;
import com.algotrading.enums.SignalType;
import com.algotrading.enums.StrategyType;
import com.algotrading.service.TradingStrategy;
import com.algotrading.util.Indicator;
import com.algotrading.util.Symbols;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

/**
 * IndexTrendStrategy — regime-filtered momentum continuation, built for index
 * F&O (works on stocks too). Entirely volume-free, because NSE index candles
 * report zero volume.
 *
 * A trade needs FOUR independent confirmations to line up:
 *   1. Regime  : ADX(14) ≥ 20 — only trade when the index is actually trending.
 *   2. Trend   : Supertrend(10, 2.5) direction AND EMA9 > EMA21 (or < for short).
 *   3. Location: price on the trend side of VWAP with VWAP sloping the same way.
 *   4. Trigger : momentum candle — closes beyond the prior candle's extreme with
 *                a body ≥ 50% of its range (conviction, not a doji drift).
 *
 * Stop: recent 3-candle swing ± 0.15·ATR, rejected if wider than 1.5·ATR
 * (over-extended bar = bad location). Target 2.2R. RSI window keeps entries out
 * of exhaustion (long 50–75, short 25–50).
 */
@Slf4j
@Component
public class IndexTrendStrategy implements TradingStrategy {

    private static final LocalTime WIN_START = LocalTime.of(9, 30);
    private static final LocalTime WIN_END   = LocalTime.of(15, 15);
    private static final double TARGET_R = 2.2;
    private static final double MIN_ADX = 20;
    private static final double MAX_RISK_ATR = 1.5;
    private static final double MIN_MOVE_PCT_INDEX = 0.25;  // option leverage: 0.25% index ≈ 10%+ premium at Δ≈0.5
    private static final double MIN_MOVE_PCT_STOCK = 0.4;   // cash equity must clear ~0.1% round-trip charges

    @Override public StrategyType getType() { return StrategyType.INDEX_TREND; }
    @Override public int minCandles()       { return 40; }

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
        double[] vwap   = Indicator.vwap(candles);
        double[] atrArr = Indicator.atr(candles, 14);
        double[] rsiArr = Indicator.rsi(closes, 14);
        double[] adxArr = Indicator.adx(candles, 14);
        Indicator.SupertrendResult st = Indicator.supertrend(candles, 10, 2.5);

        int i = n - 1;
        double price = closes[i], atr = atrArr[i], rsi = rsiArr[i], adx = adxArr[i];
        double vw = vwap[i], vwPast = vwap[Math.max(0, i - 3)];
        if (atr <= 0 || Double.isNaN(vw)) return Optional.empty();

        // 1. Regime — no trend, no trade.
        if (adx < MIN_ADX) return Optional.empty();

        double slope = vw - vwPast;
        double range = last.getHigh() - last.getLow();
        double body  = Math.abs(last.getClose() - last.getOpen());
        boolean strongBody = range > 0 && body / range >= 0.5;
        double minMovePct = Symbols.isIndex(symbol) ? MIN_MOVE_PCT_INDEX : MIN_MOVE_PCT_STOCK;

        // ── LONG ──
        boolean upTrend = st.direction[i] == 1 && ema9[i] > ema21[i] && price > vw && slope > 0;
        boolean longTrigger = strongBody && last.getClose() > last.getOpen() && last.getClose() > prev.getHigh();
        if (upTrend && longTrigger && rsi >= 50 && rsi <= 75) {
            double swing = Math.min(Math.min(last.getLow(), prev.getLow()), candles.get(n - 3).getLow());
            double stop = Indicator.round2(swing - 0.15 * atr);
            double risk = price - stop;
            if (risk <= 0 || risk > MAX_RISK_ATR * atr) return Optional.empty();
            double target = Indicator.round2(price + TARGET_R * risk);
            if ((target - price) / price * 100.0 < minMovePct) return Optional.empty();
            return Optional.of(build(symbol, SignalType.BUY, price, stop, target, rsi, adx, vw, slope, atr,
                    String.format("Index trend long | ADX %.1f | ST↑ | VWAP↑ | RSI %.1f", adx, rsi)));
        }

        // ── SHORT ──
        boolean dnTrend = st.direction[i] == -1 && ema9[i] < ema21[i] && price < vw && slope < 0;
        boolean shortTrigger = strongBody && last.getClose() < last.getOpen() && last.getClose() < prev.getLow();
        if (dnTrend && shortTrigger && rsi <= 50 && rsi >= 25) {
            double swing = Math.max(Math.max(last.getHigh(), prev.getHigh()), candles.get(n - 3).getHigh());
            double stop = Indicator.round2(swing + 0.15 * atr);
            double risk = stop - price;
            if (risk <= 0 || risk > MAX_RISK_ATR * atr) return Optional.empty();
            double target = Indicator.round2(price - TARGET_R * risk);
            if ((price - target) / price * 100.0 < minMovePct) return Optional.empty();
            return Optional.of(build(symbol, SignalType.SELL, price, stop, target, rsi, adx, vw, slope, atr,
                    String.format("Index trend short | ADX %.1f | ST↓ | VWAP↓ | RSI %.1f", adx, rsi)));
        }

        return Optional.empty();
    }

    private TradeSignalDTO build(String symbol, SignalType side, double entry, double stop, double target,
                                 double rsi, double adx, double vw, double slope, double atr, String reason) {
        return TradeSignalDTO.builder()
                .symbol(symbol).signal(side).strategy(StrategyType.INDEX_TREND)
                .entryPrice(entry).stopLoss(stop).target(target)
                .quantity(qty(entry, stop)).confidence(0.75).rrRatio(rr(entry, stop, target))
                .signalReason(reason)
                .whyFull(String.format(
                        "1) ADX %.1f ≥ 20 — the index is in a trending regime, not chop. " +
                        "2) Supertrend, EMA9/21 and VWAP all agree on %s. " +
                        "3) Momentum trigger candle closed beyond the prior bar with a strong body. " +
                        "4) Stop beyond the 3-bar swing at ₹%.2f (≤1.5×ATR), target 2.2R.",
                        adx, side == SignalType.BUY ? "up" : "down", stop))
                .indRsi(String.format("RSI(14) = %.1f", rsi))
                .indVwapDev(String.format("VWAP=₹%.2f slope=%+.3f", vw, slope))
                .indAtr(String.format("ATR(14) = %.2f", atr))
                .indExtra(String.format("ADX(14) = %.1f", adx))
                .build();
    }

    private int    qty(double e, double s) { double r = 100000 * 0.002; double d = Math.abs(e - s); return d > 0 ? Math.max(1, (int)(r / d)) : 1; }
    private double rr(double e, double s, double t) { double r = Math.abs(e - s); return r > 0 ? Indicator.round2(Math.abs(t - e) / r) : 0; }
}
