package com.algotrading.service.impl;

import com.algotrading.dto.CandleDTO;
import com.algotrading.dto.TradeSignalDTO;
import com.algotrading.enums.SignalType;
import com.algotrading.enums.StrategyType;
import com.algotrading.service.TradingStrategy;
import com.algotrading.util.Indicator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * OrbRetestStrategy — opening-range breakout retest with trend confirmation.
 *
 * BUY  after an upside ORB breakout retests support and reclaims momentum
 * SELL after a downside ORB breakdown retests resistance and resumes lower
 *
 * This is intentionally selective:
 *   - only during the morning session
 *   - only with EMA trend alignment
 *   - only when price holds on the right side of session VWAP
 *   - only when the signal candle expands again with fresh volume
 */
@Slf4j
@Component
@Order(1)
public class OrbRetestStrategy implements TradingStrategy {

    private static final LocalTime ORB_START = LocalTime.of(9, 15);
    private static final LocalTime ORB_END = LocalTime.of(9, 30);
    private static final LocalTime WINDOW_START = LocalTime.of(9, 35);
    private static final LocalTime WINDOW_END = LocalTime.of(12, 30);

    private static final double MIN_VOLUME_RATIO = 1.20;
    private static final double TARGET_R_MULT = 2.40;
    private static final double MAX_RISK_ATR_MULT = 1.80;

    @Override
    public StrategyType getType() {
        return StrategyType.ORB_RETEST;
    }

    @Override
    public int minCandles() {
        return 40;
    }

    @Override
    public Optional<TradeSignalDTO> generate(String symbol, List<CandleDTO> candles) {
        if (candles.size() < minCandles()) {
            return Optional.empty();
        }

        List<CandleDTO> sessionCandles = currentSessionCandles(candles);
        if (sessionCandles.size() < 8) {
            return Optional.empty();
        }

        CandleDTO last = sessionCandles.get(sessionCandles.size() - 1);
        CandleDTO prev = sessionCandles.get(sessionCandles.size() - 2);
        LocalTime now = last.getTimestamp().toLocalTime();
        if (now.isBefore(WINDOW_START) || now.isAfter(WINDOW_END)) {
            return Optional.empty();
        }

        OpeningRange orb = buildOpeningRange(sessionCandles);
        if (orb == null) {
            return Optional.empty();
        }

        double[] closes = Indicator.closes(candles);
        double[] ema21 = Indicator.ema(closes, 21);
        double[] ema34 = Indicator.ema(closes, 34);
        double[] rsiArr = Indicator.rsi(closes, 14);
        double[] atrArr = Indicator.atr(candles, 14);

        int globalLast = candles.size() - 1;
        int globalPrev = globalLast - 1;
        if (globalPrev < 0) {
            return Optional.empty();
        }

        double[] sessionVwap = sessionVwap(sessionCandles);
        int sessionLast = sessionCandles.size() - 1;
        int sessionPrev = sessionLast - 1;

        double price = closes[globalLast];
        double emaFast = ema21[globalLast];
        double emaSlow = ema34[globalLast];
        double rsi = rsiArr[globalLast];
        double prevRsi = rsiArr[globalPrev];
        double atr = atrArr[globalLast];
        double vwap = sessionVwap[sessionLast];
        double prevVwap = sessionVwap[sessionPrev];
        double avgVolume = Indicator.rollingAvgVolume(candles, 20);
        double volumeRatio = avgVolume > 0 ? last.getVolume() / avgVolume : 0;

        if (atr <= 0 || Double.isNaN(emaFast) || Double.isNaN(emaSlow) || Double.isNaN(rsi)
                || Double.isNaN(vwap) || Double.isNaN(prevVwap)) {
            return Optional.empty();
        }

        double closeLocation = closeLocation(last);
        double trendGapPct = emaSlow != 0 ? (emaFast - emaSlow) / emaSlow * 100.0 : 0;
        double maxRiskPerShare = atr * MAX_RISK_ATR_MULT;

        if (isLongSetup(sessionCandles, orb, sessionLast, prev, last, price, emaFast, emaSlow, rsi, prevRsi,
                vwap, prevVwap, atr, closeLocation, volumeRatio)) {
            double stop = Indicator.round2(Math.min(Math.min(prev.getLow(), last.getLow()), orb.high) - (atr * 0.08));
            double riskPerShare = price - stop;
            if (riskPerShare <= 0 || riskPerShare > maxRiskPerShare) {
                return Optional.empty();
            }

            double target = Indicator.round2(price + (riskPerShare * TARGET_R_MULT));
            double rr = rr(price, stop, target);
            double confidence = longConfidence(volumeRatio, trendGapPct, price, vwap, atr);
            int qty = qty(price, stop);

            return Optional.of(TradeSignalDTO.builder()
                    .symbol(symbol)
                    .signal(SignalType.BUY)
                    .strategy(StrategyType.ORB_RETEST)
                    .entryPrice(price)
                    .stopLoss(stop)
                    .target(target)
                    .quantity(qty)
                    .confidence(confidence)
                    .rrRatio(rr)
                    .signalReason(String.format("ORB retest long | VWAP hold | Vol %.2fx | RSI %.1f", volumeRatio, rsi))
                    .whyFull(String.format(
                            "1) Opening range high at ₹%.2f was already broken earlier, proving trend intent. " +
                            "2) Pullback held above the breakout zone / session VWAP without trend failure. " +
                            "3) Current candle reclaimed momentum by closing above the prior high on %.2fx volume. " +
                            "4) EMA21 (₹%.2f) remains above EMA34 (₹%.2f), so the broader intraday bias is still bullish. " +
                            "5) Stop sits below the retest structure at ₹%.2f and target projects %.2fR to ₹%.2f.",
                            orb.high, volumeRatio, emaFast, emaSlow, stop, TARGET_R_MULT, target))
                    .indRsi(String.format("RSI(14) = %.1f", rsi))
                    .indEmaGap(String.format("EMA21=₹%.2f  EMA34=₹%.2f  Gap=%+.2f%%", emaFast, emaSlow, trendGapPct))
                    .indVwapDev(String.format("Session VWAP = ₹%.2f  Dev=%+.2f%%", vwap, pctDev(price, vwap)))
                    .indVolRatio(String.format("Volume = %.2fx avg", volumeRatio))
                    .indAtr(String.format("ATR(14) = ₹%.2f  Risk/share=₹%.2f", atr, riskPerShare))
                    .indExtra(String.format("ORB H=₹%.2f L=₹%.2f | Close location %.2f", orb.high, orb.low, closeLocation))
                    .build());
        }

        if (isShortSetup(sessionCandles, orb, sessionLast, prev, last, price, emaFast, emaSlow, rsi, prevRsi,
                vwap, prevVwap, atr, closeLocation, volumeRatio)) {
            double stop = Indicator.round2(Math.max(Math.max(prev.getHigh(), last.getHigh()), orb.low) + (atr * 0.08));
            double riskPerShare = stop - price;
            if (riskPerShare <= 0 || riskPerShare > maxRiskPerShare) {
                return Optional.empty();
            }

            double target = Indicator.round2(price - (riskPerShare * TARGET_R_MULT));
            double rr = rr(price, stop, target);
            double confidence = shortConfidence(volumeRatio, trendGapPct, price, vwap, atr);
            int qty = qty(price, stop);

            return Optional.of(TradeSignalDTO.builder()
                    .symbol(symbol)
                    .signal(SignalType.SELL)
                    .strategy(StrategyType.ORB_RETEST)
                    .entryPrice(price)
                    .stopLoss(stop)
                    .target(target)
                    .quantity(qty)
                    .confidence(confidence)
                    .rrRatio(rr)
                    .signalReason(String.format("ORB retest short | VWAP cap | Vol %.2fx | RSI %.1f", volumeRatio, rsi))
                    .whyFull(String.format(
                            "1) Opening range low at ₹%.2f was already lost earlier, confirming downside control. " +
                            "2) Pullback failed beneath the breakdown zone / session VWAP without trend recovery. " +
                            "3) Current candle resumed lower by closing below the prior low on %.2fx volume. " +
                            "4) EMA21 (₹%.2f) remains below EMA34 (₹%.2f), so the broader intraday bias is still bearish. " +
                            "5) Stop sits above the retest structure at ₹%.2f and target projects %.2fR to ₹%.2f.",
                            orb.low, volumeRatio, emaFast, emaSlow, stop, TARGET_R_MULT, target))
                    .indRsi(String.format("RSI(14) = %.1f", rsi))
                    .indEmaGap(String.format("EMA21=₹%.2f  EMA34=₹%.2f  Gap=%+.2f%%", emaFast, emaSlow, trendGapPct))
                    .indVwapDev(String.format("Session VWAP = ₹%.2f  Dev=%+.2f%%", vwap, pctDev(price, vwap)))
                    .indVolRatio(String.format("Volume = %.2fx avg", volumeRatio))
                    .indAtr(String.format("ATR(14) = ₹%.2f  Risk/share=₹%.2f", atr, riskPerShare))
                    .indExtra(String.format("ORB H=₹%.2f L=₹%.2f | Close location %.2f", orb.high, orb.low, closeLocation))
                    .build());
        }

        return Optional.empty();
    }

    private boolean isLongSetup(List<CandleDTO> sessionCandles,
                                OpeningRange orb,
                                int sessionLast,
                                CandleDTO prev,
                                CandleDTO last,
                                double price,
                                double emaFast,
                                double emaSlow,
                                double rsi,
                                double prevRsi,
                                double vwap,
                                double prevVwap,
                                double atr,
                                double closeLocation,
                                double volumeRatio) {
        double supportCeiling = Math.max(orb.high, emaFast) + (atr * 0.25);
        double supportFloor = Math.min(orb.high, vwap) - (atr * 0.35);
        return volumeRatio >= MIN_VOLUME_RATIO
                && emaFast > emaSlow
                && price > emaFast
                && price > vwap
                && prev.getClose() >= prevVwap
                && priorBreakoutExists(sessionCandles, orb.high + (atr * 0.20), true, sessionLast - 1)
                && prev.getLow() <= supportCeiling
                && prev.getLow() >= supportFloor
                && last.getLow() >= vwap - (atr * 0.20)
                && last.getClose() > last.getOpen()
                && last.getClose() > prev.getHigh()
                && last.getClose() > orb.high
                && closeLocation >= 0.65
                && rsi >= 55 && rsi <= 88
                && rsi >= prevRsi;
    }

    private boolean isShortSetup(List<CandleDTO> sessionCandles,
                                 OpeningRange orb,
                                 int sessionLast,
                                 CandleDTO prev,
                                 CandleDTO last,
                                 double price,
                                 double emaFast,
                                 double emaSlow,
                                 double rsi,
                                 double prevRsi,
                                 double vwap,
                                 double prevVwap,
                                 double atr,
                                 double closeLocation,
                                 double volumeRatio) {
        double resistanceFloor = Math.min(orb.low, emaFast) - (atr * 0.25);
        double resistanceCeiling = Math.max(orb.low, vwap) + (atr * 0.35);
        return volumeRatio >= MIN_VOLUME_RATIO
                && emaFast < emaSlow
                && price < emaFast
                && price < vwap
                && prev.getClose() <= prevVwap
                && priorBreakoutExists(sessionCandles, orb.low - (atr * 0.20), false, sessionLast - 1)
                && prev.getHigh() >= resistanceFloor
                && prev.getHigh() <= resistanceCeiling
                && last.getHigh() <= vwap + (atr * 0.20)
                && last.getClose() < last.getOpen()
                && last.getClose() < prev.getLow()
                && last.getClose() < orb.low
                && closeLocation <= 0.35
                && rsi >= 18 && rsi <= 45
                && rsi <= prevRsi;
    }

    private List<CandleDTO> currentSessionCandles(List<CandleDTO> candles) {
        LocalDate sessionDate = candles.get(candles.size() - 1).getTimestamp().toLocalDate();
        List<CandleDTO> session = new ArrayList<CandleDTO>();
        for (CandleDTO candle : candles) {
            if (sessionDate.equals(candle.getTimestamp().toLocalDate())) {
                session.add(candle);
            }
        }
        return session;
    }

    private OpeningRange buildOpeningRange(List<CandleDTO> sessionCandles) {
        double high = Double.NEGATIVE_INFINITY;
        double low = Double.POSITIVE_INFINITY;
        boolean found = false;

        for (CandleDTO candle : sessionCandles) {
            LocalTime time = candle.getTimestamp().toLocalTime();
            if (!time.isBefore(ORB_START) && !time.isAfter(ORB_END)) {
                high = Math.max(high, candle.getHigh());
                low = Math.min(low, candle.getLow());
                found = true;
            }
        }

        return found ? new OpeningRange(high, low) : null;
    }

    private boolean priorBreakoutExists(List<CandleDTO> sessionCandles, double trigger, boolean longSide, int untilExclusive) {
        for (int i = 0; i < untilExclusive; i++) {
            CandleDTO candle = sessionCandles.get(i);
            LocalTime time = candle.getTimestamp().toLocalTime();
            if (!time.isAfter(ORB_END)) {
                continue;
            }
            if (longSide && candle.getHigh() >= trigger) {
                return true;
            }
            if (!longSide && candle.getLow() <= trigger) {
                return true;
            }
        }
        return false;
    }

    private double[] sessionVwap(List<CandleDTO> sessionCandles) {
        double[] values = new double[sessionCandles.size()];
        double cumTpVol = 0;
        double cumVol = 0;

        for (int i = 0; i < sessionCandles.size(); i++) {
            CandleDTO candle = sessionCandles.get(i);
            double typicalPrice = (candle.getHigh() + candle.getLow() + candle.getClose()) / 3.0;
            cumTpVol += typicalPrice * candle.getVolume();
            cumVol += candle.getVolume();
            values[i] = cumVol > 0 ? cumTpVol / cumVol : typicalPrice;
        }

        return values;
    }

    private double closeLocation(CandleDTO candle) {
        double range = candle.getHigh() - candle.getLow();
        return range <= 0 ? 0.5 : (candle.getClose() - candle.getLow()) / range;
    }

    private double longConfidence(double volumeRatio, double trendGapPct, double price, double vwap, double atr) {
        double score = 0.74;
        score += Math.min(0.04, Math.max(0, volumeRatio - MIN_VOLUME_RATIO) / 8.0);
        score += Math.min(0.03, Math.max(0, trendGapPct) / 25.0);
        score += Math.min(0.02, Math.max(0, price - vwap) / Math.max(atr * 10.0, 1.0));
        return Indicator.round2(Math.min(0.83, score));
    }

    private double shortConfidence(double volumeRatio, double trendGapPct, double price, double vwap, double atr) {
        double score = 0.74;
        score += Math.min(0.04, Math.max(0, volumeRatio - MIN_VOLUME_RATIO) / 8.0);
        score += Math.min(0.03, Math.max(0, -trendGapPct) / 25.0);
        score += Math.min(0.02, Math.max(0, vwap - price) / Math.max(atr * 10.0, 1.0));
        return Indicator.round2(Math.min(0.83, score));
    }

    private double pctDev(double price, double reference) {
        return reference != 0 ? ((price - reference) / reference) * 100.0 : 0;
    }

    private int qty(double entry, double stop) {
        double riskCapital = 100000 * 0.002;
        double riskPerShare = Math.abs(entry - stop);
        return riskPerShare > 0 ? Math.max(1, (int) (riskCapital / riskPerShare)) : 1;
    }

    private double rr(double entry, double stop, double target) {
        double risk = Math.abs(entry - stop);
        return risk > 0 ? Indicator.round2(Math.abs(target - entry) / risk) : 0;
    }

    private static class OpeningRange {
        private final double high;
        private final double low;

        private OpeningRange(double high, double low) {
            this.high = high;
            this.low = low;
        }
    }
}
