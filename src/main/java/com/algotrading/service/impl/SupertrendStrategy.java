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
 * SupertrendStrategy — implements TradingStrategy.
 *
 * BUY  when direction flips  -1 → +1  (bearish to bullish)
 * SELL when direction flips  +1 → -1  (bullish to bearish)
 * Stop = Supertrend line value;  Target = ±3×ATR
 */
@Slf4j
@Component
public class SupertrendStrategy implements TradingStrategy {

    private static final int    PERIOD     = 10;
    private static final double MULTIPLIER = 3.0;

    @Override
    public StrategyType getType()  { return StrategyType.SUPERTREND; }

    @Override
    public int minCandles()        { return 20; }

    @Override
    public Optional<TradeSignalDTO> generate(String symbol, List<CandleDTO> candles) {
        if (candles.size() < minCandles()) return Optional.empty();

        Indicator.SupertrendResult st = Indicator.supertrend(candles, PERIOD, MULTIPLIER);
        double[] atrArr = Indicator.atr(candles, 14);
        double[] closes = Indicator.closes(candles);
        double[] rsiArr = Indicator.rsi(closes, 14);

        int last = candles.size() - 1;
        int d0 = st.direction[last], d1 = st.direction[last - 1];
        double stVal = st.line[last];
        double atr   = atrArr[last], rsi = rsiArr[last];
        double price = closes[last];
        double dist  = Math.abs(price - stVal) / price * 100;

        if (Double.isNaN(stVal) || Double.isNaN(atr)) return Optional.empty();

        // BUY — supertrend flips bullish
        if (d1 == -1 && d0 == 1) {
            double entry = price, stop = Indicator.round2(stVal);
            double target = Indicator.round2(entry + 3 * atr);
            int qty = qty(entry, stop); double rr = rr(entry, stop, target);
            return Optional.of(TradeSignalDTO.builder()
                    .symbol(symbol).signal(SignalType.BUY).strategy(StrategyType.SUPERTREND)
                    .entryPrice(entry).stopLoss(stop).target(target)
                    .quantity(qty).confidence(0.73).rrRatio(rr)
                    .signalReason(String.format("Supertrend flipped BULLISH at ₹%.2f | RSI=%.1f", stVal, rsi))
                    .whyFull(String.format(
                            "1) Supertrend flipped BEARISH→BULLISH — strongest trend-change signal. " +
                            "2) Price crossed above ATR band at ₹%.2f. " +
                            "3) Dynamic stop self-adjusts to volatility. " +
                            "4) RSI at %.1f supports continuation. " +
                            "5) Target = 3×ATR (₹%.2f move from entry). RR=%.2f.",
                            stVal, rsi, 3 * atr, rr))
                    .indRsi(String.format("RSI(14) = %.1f", rsi))
                    .indAtr(String.format("ATR(14) = ₹%.2f", atr))
                    .indExtra(String.format("ST=₹%.2f  Dir=BULLISH  Dist=%.2f%%", stVal, dist))
                    .build());
        }

        // SELL — supertrend flips bearish
        if (d1 == 1 && d0 == -1) {
            double entry = price, stop = Indicator.round2(stVal);
            double target = Indicator.round2(entry - 3 * atr);
            int qty = qty(entry, stop); double rr = rr(entry, stop, target);
            return Optional.of(TradeSignalDTO.builder()
                    .symbol(symbol).signal(SignalType.SELL).strategy(StrategyType.SUPERTREND)
                    .entryPrice(entry).stopLoss(stop).target(target)
                    .quantity(qty).confidence(0.73).rrRatio(rr)
                    .signalReason(String.format("Supertrend flipped BEARISH at ₹%.2f | RSI=%.1f", stVal, rsi))
                    .whyFull(String.format(
                            "1) Supertrend flipped BULLISH→BEARISH — sellers in control. " +
                            "2) Price crossed below ATR band at ₹%.2f. " +
                            "3) RSI at %.1f confirms bearish direction. " +
                            "4) Stop at ₹%.2f. Target=3×ATR downside. RR=%.2f.",
                            stVal, rsi, stVal, rr))
                    .indRsi(String.format("RSI(14) = %.1f", rsi))
                    .indAtr(String.format("ATR(14) = ₹%.2f", atr))
                    .indExtra(String.format("ST=₹%.2f  Dir=BEARISH  Dist=%.2f%%", stVal, dist))
                    .build());
        }

        return Optional.empty();
    }

    private int    qty(double e, double s) { double r = 100000 * 0.002; double d = Math.abs(e - s); return d > 0 ? Math.max(1, (int)(r / d)) : 1; }
    private double rr(double e, double s, double t)  { double r = Math.abs(e - s); return r > 0 ? Indicator.round2(Math.abs(t - e) / r) : 0; }
}
