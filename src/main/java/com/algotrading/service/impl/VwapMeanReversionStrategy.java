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
 * VwapMeanReversionStrategy — implements TradingStrategy.
 *
 * BUY after price closes back inside the lower 2σ band with RSI recovery
 * SELL after price closes back inside the upper 2σ band with RSI cooling off
 * Stop goes beyond the rejection candle / 3σ band; target is capped before VWAP
 */
@Slf4j
@Component
public class VwapMeanReversionStrategy implements TradingStrategy {

    private static final double MAX_ABS_VWAP_DEV_PCT = 2.25;
    private static final double MAX_RISK_ATR_MULT = 1.25;
    private static final double TARGET_R_MULT = 1.80;

    @Override
    public StrategyType getType()  { return StrategyType.VWAP_MR; }

    @Override
    public int minCandles()        { return 30; }

    @Override
    public Optional<TradeSignalDTO> generate(String symbol, List<CandleDTO> candles) {
        if (candles.size() < minCandles()) return Optional.empty();

        double[] closes = Indicator.closes(candles);
        double[] vwapArr = Indicator.vwap(candles);
        double[] rsiArr  = Indicator.rsi(closes, 14);
        double[] stdArr  = Indicator.rollingStd(closes, 20);
        double[] atrArr  = Indicator.atr(candles, 14);

        int last  = closes.length - 1;
        int prev  = last - 1;
        if (prev < 20) return Optional.empty();

        CandleDTO lastCandle = candles.get(last);
        CandleDTO prevCandle = candles.get(prev);

        double price = closes[last], prevPrice = closes[prev];
        double vwap = vwapArr[last], prevVwap = vwapArr[prev];
        double rsi   = rsiArr[last], prevRsi = rsiArr[prev];
        double std  = stdArr[last], prevStd = stdArr[prev];
        double atr   = atrArr[last];
        double avgVolume = Indicator.rollingAvgVolume(candles, 20);
        double volumeRatio = avgVolume > 0 ? lastCandle.getVolume() / avgVolume : 1.0;

        if (std <= 0 || prevStd <= 0 || atr <= 0) return Optional.empty();
        if (Double.isNaN(vwap) || Double.isNaN(prevVwap) || Double.isNaN(rsi) || Double.isNaN(prevRsi)) {
            return Optional.empty();
        }

        double lo2 = vwap - 2 * std, hi2 = vwap + 2 * std;
        double lo3 = vwap - 3 * std, hi3 = vwap + 3 * std;
        double prevLo2 = prevVwap - 2 * prevStd, prevHi2 = prevVwap + 2 * prevStd;
        double dev = (price - vwap) / vwap * 100;
        double closeLocation = closeLocation(lastCandle);
        double maxRiskPerShare = atr * MAX_RISK_ATR_MULT;

        // BUY — reclaim the lower band after an oversold excursion
        if (Math.abs(dev) <= MAX_ABS_VWAP_DEV_PCT
                && prevPrice < prevLo2
                && price > lo2
                && price < vwap
                && price > prevPrice
                && price > lastCandle.getOpen()
                && closeLocation >= 0.55
                && rsi >= 24 && rsi < 45
                && rsi > prevRsi
                && volumeRatio >= 0.85) {
            double stop = Indicator.round2(Math.min(Math.min(lastCandle.getLow(), prevCandle.getLow()), lo3) - (atr * 0.10));
            double riskPerShare = price - stop;
            if (riskPerShare <= 0 || riskPerShare > maxRiskPerShare) return Optional.empty();

            double target = Indicator.round2(Math.min(vwap, price + (riskPerShare * TARGET_R_MULT)));
            double rr = rr(price, stop, target);
            if (rr < 1.5) return Optional.empty();

            double conf = Math.min(0.84, 0.68 + Math.max(0, rsi - prevRsi) / 100.0 + Math.max(0, volumeRatio - 1.0) / 10.0);
            int qty = qty(price, stop);
            return Optional.of(TradeSignalDTO.builder()
                    .symbol(symbol).signal(SignalType.BUY).strategy(StrategyType.VWAP_MR)
                    .entryPrice(price).stopLoss(stop).target(target)
                    .quantity(qty).confidence(conf).rrRatio(rr)
                    .signalReason(String.format("VWAP reclaim long | dev %.1f%% | RSI %.1f -> %.1f", Math.abs(dev), prevRsi, rsi))
                    .whyFull(String.format(
                            "1) Previous candle closed below the lower 2σ band (₹%.2f), showing exhaustion. " +
                            "2) Current candle reclaimed the band and closed bullish in the upper half of its range. " +
                            "3) RSI recovered from %.1f to %.1f, confirming momentum is stabilising. " +
                            "4) Stop sits below the rejection low / 3σ area at ₹%.2f to avoid inverted-risk entries. " +
                            "5) Target is capped at ₹%.2f, keeping the trade realistic before full VWAP mean reversion.",
                            prevLo2, prevRsi, rsi, stop, target))
                    .indRsi(String.format("RSI(14) = %.1f", rsi))
                    .indVwapDev(String.format("VWAP Dev = %+.2f%%  VWAP=₹%.2f  2σ: ₹%.2f–₹%.2f", dev, vwap, lo2, hi2))
                    .indVolRatio(String.format("Volume %.2fx 20-candle avg", volumeRatio))
                    .indAtr(String.format("ATR(14) = ₹%.2f  Risk/share=₹%.2f", atr, riskPerShare))
                    .indExtra(String.format("Close location %.2f | Prev close ₹%.2f | Reclaimed lower band", closeLocation, prevPrice))
                    .build());
        }

        // SELL — lose the upper band after an overbought excursion
        if (Math.abs(dev) <= MAX_ABS_VWAP_DEV_PCT
                && prevPrice > prevHi2
                && price < hi2
                && price > vwap
                && price < prevPrice
                && price < lastCandle.getOpen()
                && closeLocation <= 0.45
                && rsi > 55 && rsi <= 76
                && rsi < prevRsi
                && volumeRatio >= 0.85) {
            double stop = Indicator.round2(Math.max(Math.max(lastCandle.getHigh(), prevCandle.getHigh()), hi3) + (atr * 0.10));
            double riskPerShare = stop - price;
            if (riskPerShare <= 0 || riskPerShare > maxRiskPerShare) return Optional.empty();

            double target = Indicator.round2(Math.max(vwap, price - (riskPerShare * TARGET_R_MULT)));
            double rr = rr(price, stop, target);
            if (rr < 1.5) return Optional.empty();

            double conf = Math.min(0.84, 0.68 + Math.max(0, prevRsi - rsi) / 100.0 + Math.max(0, volumeRatio - 1.0) / 10.0);
            int qty = qty(price, stop);
            return Optional.of(TradeSignalDTO.builder()
                    .symbol(symbol).signal(SignalType.SELL).strategy(StrategyType.VWAP_MR)
                    .entryPrice(price).stopLoss(stop).target(target)
                    .quantity(qty).confidence(conf).rrRatio(rr)
                    .signalReason(String.format("VWAP rejection short | dev %.1f%% | RSI %.1f -> %.1f", dev, prevRsi, rsi))
                    .whyFull(String.format(
                            "1) Previous candle closed above the upper 2σ band (₹%.2f), showing exhaustion. " +
                            "2) Current candle lost the band and closed bearish in the lower half of its range. " +
                            "3) RSI cooled from %.1f to %.1f, confirming the overbought thrust is fading. " +
                            "4) Stop sits above the rejection high / 3σ area at ₹%.2f to avoid upside-trend traps. " +
                            "5) Target is capped at ₹%.2f, keeping the exit realistic before full VWAP reversion.",
                            prevHi2, prevRsi, rsi, stop, target))
                    .indRsi(String.format("RSI(14) = %.1f", rsi))
                    .indVwapDev(String.format("VWAP Dev = %+.2f%%  VWAP=₹%.2f  2σ: ₹%.2f–₹%.2f", dev, vwap, lo2, hi2))
                    .indVolRatio(String.format("Volume %.2fx 20-candle avg", volumeRatio))
                    .indAtr(String.format("ATR(14) = ₹%.2f  Risk/share=₹%.2f", atr, riskPerShare))
                    .indExtra(String.format("Close location %.2f | Prev close ₹%.2f | Lost upper band", closeLocation, prevPrice))
                    .build());
        }

        return Optional.empty();
    }

    private double closeLocation(CandleDTO candle) {
        double range = candle.getHigh() - candle.getLow();
        return range <= 0 ? 0.5 : (candle.getClose() - candle.getLow()) / range;
    }

    private int    qty(double e, double s) { double r = 100000 * 0.002; double d = Math.abs(e - s); return d > 0 ? Math.max(1, (int)(r / d)) : 1; }
    private double rr(double e, double s, double t)  { double r = Math.abs(e - s); return r > 0 ? Indicator.round2(Math.abs(t - e) / r) : 0; }
}
