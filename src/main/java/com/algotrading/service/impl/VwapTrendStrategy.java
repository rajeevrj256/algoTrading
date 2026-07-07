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
 * VwapTrendStrategy — trade WITH the intraday trend, enter on a pullback to VWAP.
 *
 * The opposite of the dead VWAP_MR: instead of fading extremes, it rides a trend
 * and buys the shallow pullback to a rising VWAP (sells the bounce to a falling one).
 *
 * BUY  : price > VWAP, VWAP rising, EMA9 > EMA21, last candle dipped to the
 *        VWAP/EMA9 zone then closed back up (bounce), volume ≥ average.
 * Stop : below the pullback swing low. Target : 2.5R. Min move 0.4% (beats charges).
 */
@Slf4j
@Component
public class VwapTrendStrategy implements TradingStrategy {

    private static final LocalTime WIN_START = LocalTime.of(9, 30);
    private static final LocalTime WIN_END   = LocalTime.of(14, 30);
    private static final double TARGET_R = 2.5;
    private static final double MIN_MOVE_PCT = 0.4;   // ≈4× round-trip cost

    @Override public StrategyType getType() { return StrategyType.VWAP_TREND; }
    @Override public int minCandles()       { return 30; }

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
        double[] vwap   = Indicator.vwap(candles);
        double[] ema9   = Indicator.ema(closes, 9);
        double[] ema21  = Indicator.ema(closes, 21);
        double[] atrArr = Indicator.atr(candles, 14);
        double[] rsiArr = Indicator.rsi(closes, 14);

        int i = n - 1;
        double price = closes[i], atr = atrArr[i], rsi = rsiArr[i];
        double vw = vwap[i], vwPast = vwap[Math.max(0, i - 3)];
        double avgVol = Indicator.rollingAvgVolume(candles, 20);
        double volRatio = avgVol > 0 ? last.getVolume() / avgVol : 0;
        if (atr <= 0 || Double.isNaN(vw)) return Optional.empty();

        double slope = vw - vwPast;
        boolean bounceUp = last.getClose() > last.getOpen() && last.getClose() > prev.getClose();
        boolean bounceDn = last.getClose() < last.getOpen() && last.getClose() < prev.getClose();

        // Index candles report zero volume — the volume confirm is neutral there.
        boolean volOk = avgVol <= 0 || volRatio >= 1.0;

        // ── LONG — pullback to rising VWAP ──
        boolean upTrend  = price > vw && ema9[i] > ema21[i] && slope > 0;
        boolean pulledToVwap = Math.min(last.getLow(), prev.getLow()) <= Math.max(vw, ema9[i]) * 1.0015;
        if (upTrend && pulledToVwap && bounceUp && price > vw && volOk && rsi < 70) {
            double stop = Indicator.round2(Math.min(last.getLow(), prev.getLow()) - 0.1 * atr);
            double risk = price - stop;
            if (risk <= 0) return Optional.empty();
            double target = Indicator.round2(price + TARGET_R * risk);
            if ((target - price) / price * 100.0 < MIN_MOVE_PCT) return Optional.empty();
            return Optional.of(build(symbol, SignalType.BUY, price, stop, target, rsi, vw, slope, volRatio,
                    String.format("VWAP trend pullback long | VWAP↑ | RSI %.1f | Vol %.1fx", rsi, volRatio)));
        }

        // ── SHORT — bounce into falling VWAP ──
        boolean dnTrend = price < vw && ema9[i] < ema21[i] && slope < 0;
        boolean ralliedToVwap = Math.max(last.getHigh(), prev.getHigh()) >= Math.min(vw, ema9[i]) * 0.9985;
        if (dnTrend && ralliedToVwap && bounceDn && price < vw && volOk && rsi > 30) {
            double stop = Indicator.round2(Math.max(last.getHigh(), prev.getHigh()) + 0.1 * atr);
            double risk = stop - price;
            if (risk <= 0) return Optional.empty();
            double target = Indicator.round2(price - TARGET_R * risk);
            if ((price - target) / price * 100.0 < MIN_MOVE_PCT) return Optional.empty();
            return Optional.of(build(symbol, SignalType.SELL, price, stop, target, rsi, vw, slope, volRatio,
                    String.format("VWAP trend pullback short | VWAP↓ | RSI %.1f | Vol %.1fx", rsi, volRatio)));
        }

        return Optional.empty();
    }

    private TradeSignalDTO build(String symbol, SignalType side, double entry, double stop, double target,
                                 double rsi, double vw, double slope, double volRatio, String reason) {
        return TradeSignalDTO.builder()
                .symbol(symbol).signal(side).strategy(StrategyType.VWAP_TREND)
                .entryPrice(entry).stopLoss(stop).target(target)
                .quantity(qty(entry, stop)).confidence(0.72).rrRatio(rr(entry, stop, target))
                .signalReason(reason)
                .whyFull(String.format(
                        "1) Trend aligned: price on the %s side of VWAP and VWAP sloping that way. " +
                        "2) Shallow pullback to the VWAP/EMA9 zone then a reversal candle in trend direction. " +
                        "3) Stop just beyond the pullback swing at ₹%.2f. " +
                        "4) Target = 2.5R and ≥0.4%% move, so the win clears charges.",
                        side == SignalType.BUY ? "buy" : "sell", stop))
                .indRsi(String.format("RSI(14) = %.1f", rsi))
                .indVwapDev(String.format("VWAP=₹%.2f slope=%+.3f", vw, slope))
                .indVolRatio(String.format("Volume %.2fx avg", volRatio))
                .build();
    }

    private int    qty(double e, double s) { double r = 100000 * 0.002; double d = Math.abs(e - s); return d > 0 ? Math.max(1, (int)(r / d)) : 1; }
    private double rr(double e, double s, double t) { double r = Math.abs(e - s); return r > 0 ? Indicator.round2(Math.abs(t - e) / r) : 0; }
}
