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
 * GapAndGoStrategy — implements TradingStrategy.
 *
 * Active only 09:15-09:45. Trades gap continuation with 2× volume confirmation.
 * BUY  when gap > +0.5% AND price holding above open
 * SELL when gap < -0.5% AND price holding below open
 */
@Slf4j
@Component
public class GapAndGoStrategy implements TradingStrategy {

    private static final LocalTime WINDOW_START = LocalTime.of(9, 15);
    private static final LocalTime WINDOW_END   = LocalTime.of(9, 45);

    @Override
    public StrategyType getType()  { return StrategyType.GAP_GO; }

    @Override
    public int minCandles()        { return 25; }

    @Override
    public Optional<TradeSignalDTO> generate(String symbol, List<CandleDTO> candles) {
        if (candles.size() < minCandles()) return Optional.empty();

        CandleDTO lastCandle = candles.get(candles.size() - 1);
        LocalTime now = lastCandle.getTimestamp().toLocalTime();
        if (now.isBefore(WINDOW_START) || now.isAfter(WINDOW_END)) return Optional.empty();

        // Split candles into previous-session and today
        LocalDate today = lastCandle.getTimestamp().toLocalDate();
        CandleDTO prevClose = null, todayOpen = null;
        for (CandleDTO c : candles) {
            if (c.getTimestamp().toLocalDate().isBefore(today)) prevClose = c;
            else if (todayOpen == null)                         todayOpen = c;
        }
        if (prevClose == null || todayOpen == null) return Optional.empty();

        double pc = prevClose.getClose(), openPrice = todayOpen.getOpen();
        double price = lastCandle.getClose();
        double gap  = (openPrice - pc) / pc * 100;
        double gapSize = Math.abs(openPrice - pc);

        double avgVol  = Indicator.rollingAvgVolume(candles, 20);
        double volRatio = avgVol > 0 ? lastCandle.getVolume() / avgVol : 0;
        if (volRatio < 2.0) return Optional.empty();

        double[] closes = Indicator.closes(candles);
        double rsi = Indicator.rsi(closes, 14)[closes.length - 1];

        // BUY — gap up continuation
        if (gap > 0.5 && price >= openPrice) {
            double entry = price, stop = Indicator.round2(pc);
            double target = Indicator.round2(entry + gapSize * 1.5);
            int qty = qty(entry, stop); double rr = rr(entry, stop, target);
            return Optional.of(TradeSignalDTO.builder()
                    .symbol(symbol).signal(SignalType.BUY).strategy(StrategyType.GAP_GO)
                    .entryPrice(entry).stopLoss(stop).target(target)
                    .quantity(qty).confidence(0.65).rrRatio(rr)
                    .signalReason(String.format("Gap up %.2f%% | Vol %.1fx | Holding above open", gap, volRatio))
                    .whyFull(String.format(
                            "1) Stock gapped UP %.2f%% (₹%.2f → ₹%.2f). " +
                            "2) Volume %.1fx avg — real buyers, not a false open. " +
                            "3) Price HOLDING above open — gap not filling. " +
                            "4) Stop = prev close (₹%.2f) — full fill = signal invalid. " +
                            "5) Target = Entry + 1.5× gap (₹%.2f).",
                            gap, pc, openPrice, volRatio, pc, target))
                    .indRsi(String.format("RSI(14) = %.1f", rsi))
                    .indVolRatio(String.format("Volume = %.2fx avg", volRatio))
                    .indExtra(String.format("PrevClose=₹%.2f  Open=₹%.2f  Gap=₹%.2f (%+.2f%%)", pc, openPrice, gapSize, gap))
                    .build());
        }

        // SELL — gap down continuation
        if (gap < -0.5 && price <= openPrice) {
            double entry = price, stop = Indicator.round2(pc);
            double target = Indicator.round2(entry - gapSize * 1.5);
            int qty = qty(entry, stop); double rr = rr(entry, stop, target);
            return Optional.of(TradeSignalDTO.builder()
                    .symbol(symbol).signal(SignalType.SELL).strategy(StrategyType.GAP_GO)
                    .entryPrice(entry).stopLoss(stop).target(target)
                    .quantity(qty).confidence(0.65).rrRatio(rr)
                    .signalReason(String.format("Gap down %.2f%% | Vol %.1fx | Holding below open", gap, volRatio))
                    .whyFull(String.format(
                            "1) Stock gapped DOWN %.2f%% from prev close (₹%.2f). " +
                            "2) Volume %.1fx avg — confirms real selling. " +
                            "3) Price HOLDING below open — gap continuation. " +
                            "4) Stop = prev close (₹%.2f). Target = Entry - 1.5× gap (₹%.2f).",
                            Math.abs(gap), pc, volRatio, pc, target))
                    .indRsi(String.format("RSI(14) = %.1f", rsi))
                    .indVolRatio(String.format("Volume = %.2fx avg", volRatio))
                    .indExtra(String.format("PrevClose=₹%.2f  Open=₹%.2f  Gap=₹%.2f (%+.2f%%)", pc, openPrice, gapSize, gap))
                    .build());
        }

        return Optional.empty();
    }

    private int    qty(double e, double s) { double r = 100000 * 0.002; double d = Math.abs(e - s); return d > 0 ? Math.max(1, (int)(r / d)) : 1; }
    private double rr(double e, double s, double t)  { double r = Math.abs(e - s); return r > 0 ? Indicator.round2(Math.abs(t - e) / r) : 0; }
}
