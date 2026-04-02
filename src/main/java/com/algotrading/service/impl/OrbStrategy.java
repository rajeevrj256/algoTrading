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
 * OrbStrategy — Opening Range Breakout.
 * Implements TradingStrategy.
 *
 * BUY  when price closes above 09:15-09:30 High with volume > 1.5x avg
 * SELL when price closes below 09:15-09:30 Low  with volume > 1.5x avg
 */
@Slf4j
@Component
public class OrbStrategy implements TradingStrategy {

    private static final LocalTime ORB_START = LocalTime.of(9, 15);
    private static final LocalTime ORB_END   = LocalTime.of(9, 30);

    @Override
    public StrategyType getType()  { return StrategyType.ORB; }

    @Override
    public int minCandles()        { return 5; }

    @Override
    public Optional<TradeSignalDTO> generate(String symbol, List<CandleDTO> candles) {
        if (candles.size() < minCandles()) return Optional.empty();

        double[] orb = buildOrb(candles);
        if (orb == null) return Optional.empty();

        double orbH = orb[0], orbL = orb[1], orbRange = orbH - orbL;
        CandleDTO last = candles.get(candles.size() - 1);
        CandleDTO prev = candles.get(candles.size() - 2);

        double avgVol  = Indicator.rollingAvgVolume(candles, 20);
        double volRatio = avgVol > 0 ? last.getVolume() / avgVol : 0;
        if (volRatio < 1.5) return Optional.empty();

        double[] closes = Indicator.closes(candles);
        double rsi = Indicator.rsi(closes, 14)[closes.length - 1];
        double atr = Indicator.atr(candles, 14)[candles.size() - 1];

        // BUY breakout
        if (prev.getClose() <= orbH && last.getClose() > orbH) {
            double entry = last.getClose(), stop = orbL, target = entry + orbRange * 1.5;
            int qty = qty(entry, stop); double rr = rr(entry, stop, target);
            return Optional.of(TradeSignalDTO.builder()
                    .symbol(symbol).signal(SignalType.BUY).strategy(StrategyType.ORB)
                    .entryPrice(entry).stopLoss(stop).target(target)
                    .quantity(qty).confidence(0.75).rrRatio(rr)
                    .signalReason(String.format("ORB breakout above ₹%.2f | Vol %.1fx | RSI %.1f", orbH, volRatio, rsi))
                    .whyFull(String.format(
                            "1) Price closed ABOVE ORB High (₹%.2f) for first time today. " +
                            "2) Previous candle was below — clean fresh breakout. " +
                            "3) Volume %.1fx avg confirms institutional buying. " +
                            "4) Stop at ORB Low (₹%.2f) — if price falls back inside range, breakout failed. " +
                            "5) Target = Entry + 1.5x ORB Range (₹%.2f). " +
                            "6) RSI at %.1f — room to run.",
                            orbH, volRatio, orbL, target, rsi))
                    .indRsi(String.format("RSI(14) = %.1f", rsi))
                    .indVolRatio(String.format("Volume = %.2fx avg", volRatio))
                    .indAtr(String.format("ATR(14) = ₹%.2f", atr))
                    .indExtra(String.format("ORB H=₹%.2f L=₹%.2f Range=₹%.2f", orbH, orbL, orbRange))
                    .build());
        }

        // SELL breakdown
        if (prev.getClose() >= orbL && last.getClose() < orbL) {
            double entry = last.getClose(), stop = orbH, target = entry - orbRange * 1.5;
            int qty = qty(entry, stop); double rr = rr(entry, stop, target);
            return Optional.of(TradeSignalDTO.builder()
                    .symbol(symbol).signal(SignalType.SELL).strategy(StrategyType.ORB)
                    .entryPrice(entry).stopLoss(stop).target(target)
                    .quantity(qty).confidence(0.72).rrRatio(rr)
                    .signalReason(String.format("ORB breakdown below ₹%.2f | Vol %.1fx | RSI %.1f", orbL, volRatio, rsi))
                    .whyFull(String.format(
                            "1) Price closed BELOW ORB Low (₹%.2f) — clean breakdown. " +
                            "2) Volume %.1fx avg confirms selling pressure. " +
                            "3) Stop at ORB High (₹%.2f). " +
                            "4) Target = Entry - 1.5x ORB Range (₹%.2f). " +
                            "5) RSI at %.1f supports bearish bias.",
                            orbL, volRatio, orbH, target, rsi))
                    .indRsi(String.format("RSI(14) = %.1f", rsi))
                    .indVolRatio(String.format("Volume = %.2fx avg", volRatio))
                    .indAtr(String.format("ATR(14) = ₹%.2f", atr))
                    .indExtra(String.format("ORB H=₹%.2f L=₹%.2f Range=₹%.2f", orbH, orbL, orbRange))
                    .build());
        }

        return Optional.empty();
    }

    private double[] buildOrb(List<CandleDTO> candles) {
        double h = Double.MIN_VALUE, l = Double.MAX_VALUE;
        boolean found = false;
        for (CandleDTO c : candles) {
            LocalTime t = c.getTimestamp().toLocalTime();
            if (!t.isBefore(ORB_START) && !t.isAfter(ORB_END)) {
                h = Math.max(h, c.getHigh()); l = Math.min(l, c.getLow()); found = true;
            }
        }
        return found ? new double[]{h, l} : null;
    }

    private int    qty(double e, double s) { double r = 100000 * 0.002; double d = Math.abs(e - s); return d > 0 ? Math.max(1, (int)(r / d)) : 1; }
    private double rr(double e, double s, double t)  { double r = Math.abs(e - s); return r > 0 ? Indicator.round2(Math.abs(t - e) / r) : 0; }
}
