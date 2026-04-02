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
 * BUY  when price < VWAP - 2σ AND RSI < 38
 * SELL when price > VWAP + 2σ AND RSI > 62
 * Stop  = ±3σ band;  Target = VWAP
 */
@Slf4j
@Component
public class VwapMeanReversionStrategy implements TradingStrategy {

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
        double price = closes[last], vwap = vwapArr[last];
        double rsi   = rsiArr[last], std  = stdArr[last];
        double atr   = atrArr[last];

        if (std <= 0 || Double.isNaN(vwap) || Double.isNaN(rsi)) return Optional.empty();

        double lo2 = vwap - 2 * std, hi2 = vwap + 2 * std;
        double lo3 = vwap - 3 * std, hi3 = vwap + 3 * std;
        double dev = (price - vwap) / vwap * 100;

        // BUY — oversold below 2σ
        if (price < lo2 && rsi < 38) {
            double conf = Math.min(0.88, 0.70 + (38 - rsi) / 100.0);
            int qty = qty(price, lo3); double rr = rr(price, lo3, vwap);
            return Optional.of(TradeSignalDTO.builder()
                    .symbol(symbol).signal(SignalType.BUY).strategy(StrategyType.VWAP_MR)
                    .entryPrice(price).stopLoss(Indicator.round2(lo3)).target(Indicator.round2(vwap))
                    .quantity(qty).confidence(conf).rrRatio(rr)
                    .signalReason(String.format("Oversold %.1f%% below VWAP | RSI=%.1f | 2σ band crossed", Math.abs(dev), rsi))
                    .whyFull(String.format(
                            "1) Price is %.2f%% BELOW VWAP — extreme statistical deviation. " +
                            "2) Crossed below 2σ lower band (₹%.2f) — rare event ~5%% of candles. " +
                            "3) RSI at %.1f confirms deeply oversold (threshold 38). " +
                            "4) VWAP is institutional benchmark — funds buy below it to reduce avg cost. " +
                            "5) Stop at 3σ band (₹%.2f). Target = VWAP (₹%.2f).",
                            Math.abs(dev), lo2, rsi, lo3, vwap))
                    .indRsi(String.format("RSI(14) = %.1f", rsi))
                    .indVwapDev(String.format("VWAP Dev = %+.2f%%  VWAP=₹%.2f  2σ: ₹%.2f–₹%.2f", dev, vwap, lo2, hi2))
                    .indAtr(String.format("ATR(14) = ₹%.2f", atr))
                    .build());
        }

        // SELL — overbought above 2σ
        if (price > hi2 && rsi > 62) {
            double conf = Math.min(0.88, 0.70 + (rsi - 62) / 100.0);
            int qty = qty(price, hi3); double rr = rr(price, hi3, vwap);
            return Optional.of(TradeSignalDTO.builder()
                    .symbol(symbol).signal(SignalType.SELL).strategy(StrategyType.VWAP_MR)
                    .entryPrice(price).stopLoss(Indicator.round2(hi3)).target(Indicator.round2(vwap))
                    .quantity(qty).confidence(conf).rrRatio(rr)
                    .signalReason(String.format("Overbought %.1f%% above VWAP | RSI=%.1f | 2σ band crossed", dev, rsi))
                    .whyFull(String.format(
                            "1) Price is %.2f%% ABOVE VWAP — extreme overbought deviation. " +
                            "2) Crossed above 2σ upper band (₹%.2f) — exhaustion zone. " +
                            "3) RSI at %.1f confirms overbought (threshold 62). " +
                            "4) Stop at 3σ band (₹%.2f). Target = VWAP (₹%.2f).",
                            dev, hi2, rsi, hi3, vwap))
                    .indRsi(String.format("RSI(14) = %.1f", rsi))
                    .indVwapDev(String.format("VWAP Dev = %+.2f%%  VWAP=₹%.2f  2σ: ₹%.2f–₹%.2f", dev, vwap, lo2, hi2))
                    .indAtr(String.format("ATR(14) = ₹%.2f", atr))
                    .build());
        }

        return Optional.empty();
    }

    private int    qty(double e, double s) { double r = 100000 * 0.002; double d = Math.abs(e - s); return d > 0 ? Math.max(1, (int)(r / d)) : 1; }
    private double rr(double e, double s, double t)  { double r = Math.abs(e - s); return r > 0 ? Indicator.round2(Math.abs(t - e) / r) : 0; }
}
