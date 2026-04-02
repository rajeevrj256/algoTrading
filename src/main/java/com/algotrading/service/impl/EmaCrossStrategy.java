package com.algotrading.service.impl;

import com.algotrading.dto.CandleDTO;
import com.algotrading.dto.TradeSignalDTO;
import com.algotrading.enums.SignalType;
import com.algotrading.enums.StrategyType;
import com.algotrading.service.TradingStrategy;
import com.algotrading.util.Indicator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * EmaCrossStrategy — Golden/Death Cross.
 * Implements TradingStrategy.
 *
 * BUY  when EMA9 crosses above EMA21 AND RSI > 52 AND MACD histogram rising
 * SELL when EMA9 crosses below EMA21 AND RSI < 48 AND MACD histogram falling
 * Stop = ±1.5×ATR;  Target = ±2.5×ATR
 */
@Slf4j
@Component
public class EmaCrossStrategy implements TradingStrategy {

    @Override
    public StrategyType getType()  { return StrategyType.EMA_CROSS; }

    @Override
    public int minCandles()        { return 35; }

    @Override
    public Optional<TradeSignalDTO> generate(String symbol, List<CandleDTO> candles) {
        if (candles.size() < minCandles()) return Optional.empty();

        double[] closes = Indicator.closes(candles);
        double[] ema9   = Indicator.ema(closes, 9);
        double[] ema21  = Indicator.ema(closes, 21);
        double[] ema50  = Indicator.ema(closes, 50);
        double[] rsiArr = Indicator.rsi(closes, 14);
        double[] atrArr = Indicator.atr(candles, 14);
        double[][] macd = Indicator.macd(closes, 12, 26, 9);

        int last = closes.length - 1;
        double f0 = ema9[last],  f1 = ema9[last - 1];
        double s0 = ema21[last], s1 = ema21[last - 1];
        double e50 = ema50[last], rsi = rsiArr[last];
        double atr = atrArr[last];
        double mh0 = macd[2][last], mh1 = macd[2][last - 1];
        double price = closes[last];

        if (Double.isNaN(f0) || Double.isNaN(s0)) return Optional.empty();

        double gap   = (f0 - s0) / s0 * 100;
        String trend = price > e50 ? "UPTREND" : "DOWNTREND";

        // BUY — golden cross
        if (f1 <= s1 && f0 > s0 && rsi > 52 && mh0 > mh1) {
            double entry = price, stop = Indicator.round2(entry - 1.5 * atr);
            double target = Indicator.round2(entry + 2.5 * atr);
            int qty = qty(entry, stop); double rr = rr(entry, stop, target);
            return Optional.of(TradeSignalDTO.builder()
                    .symbol(symbol).signal(SignalType.BUY).strategy(StrategyType.EMA_CROSS)
                    .entryPrice(entry).stopLoss(stop).target(target)
                    .quantity(qty).confidence(0.70).rrRatio(rr)
                    .signalReason(String.format("Golden cross EMA9>EMA21 | RSI=%.1f | MACD hist rising", rsi))
                    .whyFull(String.format(
                            "1) EMA9 (%.2f) crossed ABOVE EMA21 (%.2f) — golden cross. " +
                            "2) RSI at %.1f (above 52) — real bullish momentum. " +
                            "3) MACD histogram rising (%.4f→%.4f). " +
                            "4) Price in %s vs EMA50 (₹%.2f). " +
                            "5) Stop=1.5×ATR, Target=2.5×ATR → RR=%.2f.",
                            f0, s0, rsi, mh1, mh0, trend, e50, rr))
                    .indRsi(String.format("RSI(14) = %.1f", rsi))
                    .indEmaGap(String.format("EMA Gap=%+.3f%%  EMA9=₹%.2f EMA21=₹%.2f EMA50=₹%.2f", gap, f0, s0, e50))
                    .indAtr(String.format("ATR(14) = ₹%.2f", atr))
                    .indExtra(String.format("MACD Hist: %.4f (prev %.4f) | %s", mh0, mh1, trend))
                    .build());
        }

        // SELL — death cross
        if (f1 >= s1 && f0 < s0 && rsi < 48 && mh0 < mh1) {
            double entry = price, stop = Indicator.round2(entry + 1.5 * atr);
            double target = Indicator.round2(entry - 2.5 * atr);
            int qty = qty(entry, stop); double rr = rr(entry, stop, target);
            return Optional.of(TradeSignalDTO.builder()
                    .symbol(symbol).signal(SignalType.SELL).strategy(StrategyType.EMA_CROSS)
                    .entryPrice(entry).stopLoss(stop).target(target)
                    .quantity(qty).confidence(0.70).rrRatio(rr)
                    .signalReason(String.format("Death cross EMA9<EMA21 | RSI=%.1f | MACD hist falling", rsi))
                    .whyFull(String.format(
                            "1) EMA9 (%.2f) crossed BELOW EMA21 (%.2f) — death cross. " +
                            "2) RSI at %.1f (below 48) — bearish momentum confirmed. " +
                            "3) MACD histogram falling (%.4f→%.4f). " +
                            "4) Price in %s vs EMA50 (₹%.2f). " +
                            "5) Stop=1.5×ATR, Target=2.5×ATR → RR=%.2f.",
                            f0, s0, rsi, mh1, mh0, trend, e50, rr))
                    .indRsi(String.format("RSI(14) = %.1f", rsi))
                    .indEmaGap(String.format("EMA Gap=%+.3f%%  EMA9=₹%.2f EMA21=₹%.2f EMA50=₹%.2f", gap, f0, s0, e50))
                    .indAtr(String.format("ATR(14) = ₹%.2f", atr))
                    .indExtra(String.format("MACD Hist: %.4f (prev %.4f) | %s", mh0, mh1, trend))
                    .build());
        }

        return Optional.empty();
    }

    private int    qty(double e, double s) { double r = 100000 * 0.002; double d = Math.abs(e - s); return d > 0 ? Math.max(1, (int)(r / d)) : 1; }
    private double rr(double e, double s, double t)  { double r = Math.abs(e - s); return r > 0 ? Indicator.round2(Math.abs(t - e) / r) : 0; }
}
