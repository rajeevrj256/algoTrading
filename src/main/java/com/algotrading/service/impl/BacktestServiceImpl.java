package com.algotrading.service.impl;

import com.algotrading.dto.BacktestResultDTO;
import com.algotrading.dto.CandleDTO;
import com.algotrading.dto.TradeSignalDTO;
import com.algotrading.enums.SignalType;
import com.algotrading.enums.StrategyType;
import com.algotrading.model.TradeCharges;
import com.algotrading.service.BacktestService;
import com.algotrading.service.CandleHistoryService;
import com.algotrading.service.ChargesService;
import com.algotrading.service.DataFeedService;
import com.algotrading.service.TradingStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * BacktestServiceImpl — event-driven replay of historical candles.
 *
 * For each candle index i (≥ minCandles) the strategy sees ONLY candles[0..i]
 * (no look-ahead) and may emit a signal on candle i's close. The trade is then
 * simulated forward candle-by-candle using each candle's high/low to detect
 * stop/target, with the same breakeven+trailing logic as the live broker, an
 * intraday EOD square-off at each day boundary, slippage on both fills, and real
 * charges. After an exit the walk resumes past it (no overlapping positions).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BacktestServiceImpl implements BacktestService {

    /** Below this many stored bars, fall back to a live feed pull for the symbol. */
    private static final int MIN_DB_CANDLES = 50;

    private final List<TradingStrategy> strategies;
    private final DataFeedService dataFeedService;
    private final ChargesService chargesService;
    private final CandleHistoryService candleHistoryService;

    @Value("${broker.slippage-pct:0.02}")
    private double slippagePct;

    @Value("${broker.trailing.enabled:true}")
    private boolean trailingEnabled;
    @Value("${broker.trailing.breakeven-trigger-r:1.0}")
    private double breakevenTriggerR;
    @Value("${broker.trailing.trail-start-r:1.5}")
    private double trailStartR;
    @Value("${broker.trailing.trail-giveback-r:1.0}")
    private double trailGivebackR;

    @Override
    public List<BacktestResultDTO> run(List<String> symbols, StrategyType type, int count) {
        Map<StrategyType, Agg> aggs = new LinkedHashMap<>();

        for (String rawSymbol : symbols) {
            String symbol = rawSymbol.trim().toUpperCase();
            if (symbol.isEmpty()) continue;

            List<CandleDTO> candles = loadCandles(symbol, count);
            if (candles == null || candles.size() < 10) {
                log.warn("[Backtest] {} — not enough candles ({})", symbol, candles == null ? 0 : candles.size());
                continue;
            }

            for (TradingStrategy strat : strategies) {
                if (type != null && strat.getType() != type) continue;
                Agg agg = aggs.computeIfAbsent(strat.getType(), k -> new Agg());
                int before = agg.trades;
                replay(symbol, strat, candles, agg);
                if (agg.trades > before) agg.symbols.add(symbol);
            }
        }

        List<BacktestResultDTO> out = new ArrayList<>();
        for (Map.Entry<StrategyType, Agg> e : aggs.entrySet()) {
            out.add(e.getValue().toDto(e.getKey().name()));
        }
        out.sort((a, b) -> Double.compare(b.getAvgPnl(), a.getAvgPnl()));
        return out;
    }

    /** Prefer stored history (real depth); fall back to a live feed pull. */
    private List<CandleDTO> loadCandles(String symbol, int count) {
        try {
            List<CandleDTO> stored = candleHistoryService.load(symbol, count);
            if (stored != null && stored.size() >= MIN_DB_CANDLES) {
                log.info("[Backtest] {} — using {} stored candles", symbol, stored.size());
                return stored;
            }
        } catch (Exception e) {
            log.warn("[Backtest] candle_history read failed for {}: {}", symbol, e.getMessage());
        }
        try {
            List<CandleDTO> live = dataFeedService.getCandles(symbol, count);
            log.info("[Backtest] {} — using {} live feed candles (history too thin)",
                    symbol, live == null ? 0 : live.size());
            return live;
        } catch (Exception e) {
            log.warn("[Backtest] live candle fetch failed for {}: {}", symbol, e.getMessage());
            return null;
        }
    }

    // ── replay one strategy over one symbol's candles ─────────

    private void replay(String symbol, TradingStrategy strat, List<CandleDTO> candles, Agg agg) {
        int n = candles.size();
        int i = Math.max(strat.minCandles(), 2);
        while (i < n - 1) {
            TradeSignalDTO signal;
            try {
                signal = strat.generate(symbol, candles.subList(0, i + 1)).orElse(null);
            } catch (Exception e) {
                signal = null;
            }
            if (signal == null || signal.getQuantity() <= 0 || signal.getStopLoss() <= 0) {
                i++;
                continue;
            }
            int exitIndex = simulateTrade(signal, candles, i, agg);
            i = Math.max(exitIndex + 1, i + 1);   // resume past the exit, no overlap
        }
    }

    /** Returns the candle index at which the trade exited. */
    private int simulateTrade(TradeSignalDTO signal, List<CandleDTO> candles, int entryIdx, Agg agg) {
        boolean isLong = signal.getSignal() == SignalType.BUY;
        int qty = signal.getQuantity();

        double slip = signal.getEntryPrice() * slippagePct / 100.0;
        double entry = isLong ? signal.getEntryPrice() + slip : signal.getEntryPrice() - slip;
        double initialStop = signal.getStopLoss();
        double target = signal.getTarget();
        double r = Math.abs(entry - initialStop);
        if (r <= 0) return entryIdx;

        double stop = initialStop;
        double peak = entry;
        LocalDate entryDay = dayOf(candles.get(entryIdx));

        double exitPrice = candles.get(candles.size() - 1).getClose();
        int exitIdx = candles.size() - 1;

        for (int j = entryIdx + 1; j < candles.size(); j++) {
            CandleDTO c = candles.get(j);

            // Intraday EOD square-off at a day change → exit at previous candle close.
            if (entryDay != null && !entryDay.equals(dayOf(c))) {
                exitPrice = candles.get(j - 1).getClose();
                exitIdx = j - 1;
                break;
            }

            // Trailing update from this candle's favorable extreme.
            peak = isLong ? Math.max(peak, c.getHigh()) : Math.min(peak, c.getLow());
            stop = trail(isLong, entry, initialStop, stop, peak, r);

            boolean stopHit   = isLong ? c.getLow()  <= stop   : c.getHigh() >= stop;
            boolean targetHit = isLong ? c.getHigh() >= target : c.getLow()  <= target;

            if (stopHit) {                 // conservative: stop wins ties
                exitPrice = stop; exitIdx = j; break;
            }
            if (targetHit) {
                exitPrice = target; exitIdx = j; break;
            }
        }

        // Slippage on exit too (adverse) — honest fill model.
        double exitSlip = exitPrice * slippagePct / 100.0;
        double filledExit = isLong ? exitPrice - exitSlip : exitPrice + exitSlip;

        TradeCharges ch = chargesService.calculate(signal.getSignal(), entry, filledExit, qty);
        double rMultiple = (r * qty) > 0 ? ch.getNetPnl() / (r * qty) : 0;
        agg.add(ch.getGrossPnl(), ch.getTotalCharges(), ch.getNetPnl(), rMultiple);
        return exitIdx;
    }

    private double trail(boolean isLong, double entry, double initialStop, double curStop, double peak, double r) {
        if (!trailingEnabled || r <= 0) return curStop;
        double peakR = isLong ? (peak - entry) / r : (entry - peak) / r;
        if (peakR < breakevenTriggerR) return curStop;
        double lockedR = peakR >= trailStartR ? Math.max(0.0, peakR - trailGivebackR) : 0.0;
        double newStop = isLong ? entry + lockedR * r : entry - lockedR * r;
        if (isLong)  return Math.max(curStop, newStop);
        return Math.min(curStop, newStop);
    }

    private LocalDate dayOf(CandleDTO c) {
        return c.getTimestamp() != null ? c.getTimestamp().toLocalDate() : null;
    }

    // ── accumulator ───────────────────────────────────────────

    private static final class Agg {
        final java.util.Set<String> symbols = new java.util.HashSet<>();
        int trades, wins, losses;
        double gross, charges, net, rSum;
        double grossWins, grossLosses;   // for profit factor (net)
        double best = Double.NEGATIVE_INFINITY, worst = Double.POSITIVE_INFINITY;

        void add(double grossPnl, double chargeAmt, double netPnl, double rMultiple) {
            trades++;
            gross += grossPnl; charges += chargeAmt; net += netPnl; rSum += rMultiple;
            if (netPnl > 0) { wins++; grossWins += netPnl; } else { losses++; grossLosses += Math.abs(netPnl); }
            best = Math.max(best, netPnl);
            worst = Math.min(worst, netPnl);
        }

        BacktestResultDTO toDto(String strategy) {
            double winRate = trades > 0 ? 100.0 * wins / trades : 0;
            double avgPnl  = trades > 0 ? net / trades : 0;
            double avgR    = trades > 0 ? rSum / trades : 0;
            double pf      = grossLosses > 0 ? grossWins / grossLosses : (grossWins > 0 ? Double.POSITIVE_INFINITY : 0);
            return BacktestResultDTO.builder()
                    .strategy(strategy)
                    .symbols(symbols.size())
                    .trades(trades).wins(wins).losses(losses)
                    .winRate(r2(winRate))
                    .grossPnl(r2(gross)).totalCharges(r2(charges)).netPnl(r2(net))
                    .avgPnl(r2(avgPnl))
                    .avgR(Math.round(avgR * 1000.0) / 1000.0)
                    .profitFactor(Double.isInfinite(pf) ? pf : r2(pf))
                    .bestTrade(trades > 0 ? r2(best) : 0)
                    .worstTrade(trades > 0 ? r2(worst) : 0)
                    .build();
        }

        static double r2(double v) { return Math.round(v * 100.0) / 100.0; }
    }
}
