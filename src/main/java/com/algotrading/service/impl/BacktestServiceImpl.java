package com.algotrading.service.impl;

import com.algotrading.config.FnoProperties;
import com.algotrading.dto.BacktestResultDTO;
import com.algotrading.dto.CandleDTO;
import com.algotrading.dto.TradeSignalDTO;
import com.algotrading.enums.InstrumentType;
import com.algotrading.enums.OptionType;
import com.algotrading.enums.Segment;
import com.algotrading.enums.SignalType;
import com.algotrading.enums.StrategyType;
import com.algotrading.model.OptionContract;
import com.algotrading.model.TradeCharges;
import com.algotrading.service.BacktestService;
import com.algotrading.service.CandleHistoryService;
import com.algotrading.service.ChargesService;
import com.algotrading.service.DataFeedService;
import com.algotrading.service.GrowwHistoricalService;
import com.algotrading.service.OptionChainService;
import com.algotrading.service.StrategyService;
import com.algotrading.service.TradingStrategy;
import com.algotrading.util.OptionPricing;
import com.algotrading.util.Symbols;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * BacktestServiceImpl — event-driven replay of historical candles.
 *
 * For each candle index i (≥ minCandles) the strategy sees ONLY candles[0..i]
 * (no look-ahead) and may emit a signal on candle i's close. The trade is then
 * simulated forward candle-by-candle using each candle's high/low to detect
 * stop/target, with the same breakeven+trailing logic as the live broker, an
 * intraday EOD square-off at each day boundary, slippage on both fills, and real
 * charges. After an exit the walk resumes past it (no overlapping positions).
 *
 * Two split modes:
 *   - EQUITY : cash-frame replay, cash charges                     ({@link #runEquity})
 *   - FNO    : index signal → ATM option, simulated on REAL Groww  ({@link #runFno})
 *              FNO premium candles, exit on the underlying frame,
 *              premium hard-stop, FNO charges
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BacktestServiceImpl implements BacktestService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);
    private static final double MIN_PREMIUM = 1.0;

    /** Below this many stored bars, fall back to a live feed pull for the symbol. */
    private static final int MIN_DB_CANDLES = 50;

    private final List<TradingStrategy> strategies;
    private final DataFeedService dataFeedService;
    private final ChargesService chargesService;
    private final CandleHistoryService candleHistoryService;
    private final GrowwHistoricalService growwHistoricalService;
    private final OptionChainService optionChainService;
    private final StrategyService strategyService;
    private final FnoProperties fnoProperties;

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

    // ── EQUITY / generic underlying replay ────────────────────

    @Override
    public List<BacktestResultDTO> run(List<String> symbols, StrategyType type, int count) {
        Map<StrategyType, Agg> aggs = new LinkedHashMap<StrategyType, Agg>();
        for (String rawSymbol : symbols) {
            String symbol = rawSymbol.trim().toUpperCase();
            if (symbol.isEmpty()) continue;
            List<CandleDTO> candles = loadCandles(symbol, count);
            if (candles == null || candles.size() < 10) {
                log.warn("[Backtest] {} — not enough candles ({})", symbol, candles == null ? 0 : candles.size());
                continue;
            }
            replayEquityAllStrategies(symbol, type, null, candles, aggs);
        }
        return toResults(aggs, "EQUITY");
    }

    @Override
    public List<BacktestResultDTO> runEquity(List<String> symbols, StrategyType type, int lookbackDays) {
        Map<StrategyType, Agg> aggs = new LinkedHashMap<StrategyType, Agg>();
        Set<StrategyType> segmentSet = type != null ? null : strategyService.strategiesForSegment(Segment.EQUITY);
        for (String rawSymbol : symbols) {
            String symbol = rawSymbol.trim().toUpperCase();
            if (symbol.isEmpty()) continue;
            List<CandleDTO> candles = growwHistoricalService.equityCandles(symbol, lookbackDays);
            if (candles == null || candles.size() < 30) {
                log.warn("[Backtest][EQUITY] {} — not enough Groww candles ({})",
                        symbol, candles == null ? 0 : candles.size());
                continue;
            }
            log.info("[Backtest][EQUITY] {} — {} candles over {}d", symbol, candles.size(), lookbackDays);
            replayEquityAllStrategies(symbol, type, segmentSet, candles, aggs);
        }
        return toResults(aggs, "EQUITY");
    }

    private void replayEquityAllStrategies(String symbol, StrategyType type, Set<StrategyType> segmentSet,
                                           List<CandleDTO> candles, Map<StrategyType, Agg> aggs) {
        for (TradingStrategy strat : strategies) {
            if (type != null && strat.getType() != type) continue;
            if (segmentSet != null && !segmentSet.contains(strat.getType())) continue;
            Agg agg = aggs.computeIfAbsent(strat.getType(), k -> new Agg());
            int before = agg.trades;
            replayEquity(symbol, strat, candles, agg);
            if (agg.trades > before) agg.symbols.add(symbol);
        }
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

    private void replayEquity(String symbol, TradingStrategy strat, List<CandleDTO> candles, Agg agg) {
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
            int exitIndex = simulateEquityTrade(signal, candles, i, agg);
            i = Math.max(exitIndex + 1, i + 1);
        }
    }

    /** Returns the candle index at which the trade exited. */
    private int simulateEquityTrade(TradeSignalDTO signal, List<CandleDTO> candles, int entryIdx, Agg agg) {
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
            if (entryDay != null && !entryDay.equals(dayOf(c))) {
                exitPrice = candles.get(j - 1).getClose();
                exitIdx = j - 1;
                break;
            }
            peak = isLong ? Math.max(peak, c.getHigh()) : Math.min(peak, c.getLow());
            stop = trail(isLong, entry, initialStop, stop, peak, r);

            boolean stopHit   = isLong ? c.getLow()  <= stop   : c.getHigh() >= stop;
            boolean targetHit = isLong ? c.getHigh() >= target : c.getLow()  <= target;
            if (stopHit)   { exitPrice = stop;   exitIdx = j; break; }
            if (targetHit) { exitPrice = target; exitIdx = j; break; }
        }

        double exitSlip = exitPrice * slippagePct / 100.0;
        double filledExit = isLong ? exitPrice - exitSlip : exitPrice + exitSlip;

        TradeCharges ch = chargesService.calculate(signal.getSignal(), entry, filledExit, qty);
        double rMultiple = (r * qty) > 0 ? ch.getNetPnl() / (r * qty) : 0;
        agg.add(ch.getGrossPnl(), ch.getTotalCharges(), ch.getNetPnl(), rMultiple);
        return exitIdx;
    }

    // ── F&O (index options) replay on REAL Groww premium candles ──

    @Override
    public List<BacktestResultDTO> runFno(List<String> symbols, StrategyType type, int lookbackDays) {
        Map<StrategyType, Agg> aggs = new LinkedHashMap<StrategyType, Agg>();
        Set<StrategyType> segmentSet = type != null ? null : strategyService.strategiesForSegment(Segment.FNO);
        for (String rawSymbol : symbols) {
            String symbol = rawSymbol.trim().toUpperCase();
            if (symbol.isEmpty()) continue;

            String canonical = Symbols.canonicalIndex(symbol);
            if (canonical == null || !optionChainService.isOptionUnderlying(symbol)) {
                log.warn("[Backtest][FNO] {} — not a configured index underlying, skipped", symbol);
                continue;
            }

            List<CandleDTO> underlying = growwHistoricalService.equityCandles(canonical, lookbackDays);
            if (underlying == null || underlying.size() < 30) {
                log.warn("[Backtest][FNO] {} — not enough underlying candles ({})",
                        canonical, underlying == null ? 0 : underlying.size());
                continue;
            }
            log.info("[Backtest][FNO] {} — {} underlying candles over {}d",
                    canonical, underlying.size(), lookbackDays);

            for (TradingStrategy strat : strategies) {
                if (type != null && strat.getType() != type) continue;
                if (segmentSet != null && !segmentSet.contains(strat.getType())) continue;
                Agg agg = aggs.computeIfAbsent(strat.getType(), k -> new Agg());
                int before = agg.trades;
                replayFno(symbol, strat, underlying, agg);
                if (agg.trades > before) agg.symbols.add(canonical);
            }
        }
        return toResults(aggs, "FNO");
    }

    private void replayFno(String scanSymbol, TradingStrategy strat, List<CandleDTO> candles, Agg agg) {
        int n = candles.size();
        int i = Math.max(strat.minCandles(), 2);
        while (i < n - 1) {
            TradeSignalDTO signal;
            try {
                signal = strat.generate(scanSymbol, candles.subList(0, i + 1)).orElse(null);
            } catch (Exception e) {
                signal = null;
            }
            if (signal == null || signal.getStopLoss() <= 0 || signal.getTarget() <= 0) {
                i++;
                continue;
            }
            int exitIndex = simulateOptionTrade(scanSymbol, signal, candles, i, agg);
            i = Math.max(exitIndex + 1, i + 1);
        }
    }

    /**
     * Option-frame simulation on real Groww FNO premium candles. Exit is decided on
     * the underlying index frame (faithful to the live checkOptionExit), the fill is
     * the real option premium at the exit bar, and a premium hard-stop guards theta/IV
     * bleed. Returns the underlying candle index at which the trade exited.
     */
    private int simulateOptionTrade(String scanSymbol, TradeSignalDTO signal,
                                    List<CandleDTO> candles, int entryIdx, Agg agg) {
        CandleDTO entryCandle = candles.get(entryIdx);
        LocalDateTime entryTs = entryCandle.getTimestamp();
        if (entryTs == null) { agg.skip(); return entryIdx; }
        double spot = signal.getEntryPrice();

        OptionContract contract = optionChainService
                .resolveContractAsOf(scanSymbol, signal.getSignal(), spot, entryTs).orElse(null);
        if (contract == null) { agg.skip(); return entryIdx; }

        // Paper F&O squares off intraday → entry and exit are same-day; option data = entry day.
        LocalDate day = entryTs.toLocalDate();
        List<CandleDTO> optCandles = growwHistoricalService.optionCandles(contract.getTradingSymbol(), day, day);
        if (optCandles == null || optCandles.isEmpty()) { agg.skip(); return entryIdx; }

        Map<LocalDateTime, CandleDTO> optByTs = new HashMap<LocalDateTime, CandleDTO>();
        for (CandleDTO oc : optCandles) {
            if (oc.getTimestamp() != null) optByTs.put(oc.getTimestamp(), oc);
        }
        CandleDTO entryOpt = optByTs.get(entryTs);
        if (entryOpt == null || entryOpt.getClose() < MIN_PREMIUM) { agg.skip(); return entryIdx; }

        boolean longFrame = contract.getOptionType() == OptionType.CE;

        // Entry fill (buy the option) + slippage.
        double entryFill = round2(entryOpt.getClose() * (1 + slippagePct / 100.0));

        // Premium stop = delta-mapped underlying risk, floored by the hard stop (mirror live).
        double tYears = yearsToExpiry(entryTs, contract.getExpiry());
        double absDelta = clamp(Math.abs(OptionPricing.delta(contract.getOptionType(), spot, contract.getStrike(),
                tYears, fnoProperties.getSyntheticIv(), fnoProperties.getRiskFreeRate())), 0.30, 0.90);
        double underlyingRisk = Math.abs(signal.getEntryPrice() - signal.getStopLoss());
        if (underlyingRisk <= 0) { agg.skip(); return entryIdx; }
        double hardStop = entryFill * (1 - fnoProperties.getPremiumHardStopPct() / 100.0);
        double premiumStop = Math.max(round2(entryFill - absDelta * underlyingRisk), round2(hardStop));
        premiumStop = Math.max(premiumStop, 0.05);
        if (premiumStop >= entryFill) { agg.skip(); return entryIdx; }

        int lots = optionChainService.sizeOptionLots(entryFill, premiumStop, contract.getLotSize());
        if (lots <= 0) { agg.skip(); return entryIdx; }
        int qty = lots * contract.getLotSize();

        // Underlying-frame exit sim (same breakeven+trailing as the live option broker).
        double uEntry = signal.getEntryPrice();
        double uInitStop = signal.getStopLoss();
        double uTarget = signal.getTarget();
        double r = Math.abs(uEntry - uInitStop);
        if (r <= 0) { agg.skip(); return entryIdx; }
        double uStop = uInitStop;
        double uPeak = uEntry;

        double lastPrem = entryOpt.getClose();
        double exitPrem = lastPrem;
        int exitIdx = entryIdx;

        for (int j = entryIdx + 1; j < candles.size(); j++) {
            CandleDTO c = candles.get(j);

            // Intraday EOD square-off at a day change → exit on the previous bar.
            if (!day.equals(dayOf(c))) {
                exitIdx = j - 1;
                exitPrem = premiumClose(optByTs, candles.get(j - 1).getTimestamp(), lastPrem);
                break;
            }

            uPeak = longFrame ? Math.max(uPeak, c.getHigh()) : Math.min(uPeak, c.getLow());
            uStop = trail(longFrame, uEntry, uInitStop, uStop, uPeak, r);

            CandleDTO opt = optByTs.get(c.getTimestamp());
            double barPrem = opt != null ? opt.getClose() : lastPrem;
            double barPremLow = opt != null ? opt.getLow() : lastPrem;
            if (opt != null) lastPrem = barPrem;

            boolean targetHit = longFrame ? c.getHigh() >= uTarget : c.getLow()  <= uTarget;
            boolean uStopHit  = longFrame ? c.getLow()  <= uStop   : c.getHigh() >= uStop;
            boolean premStopHit = opt != null && barPremLow <= premiumStop;

            if (targetHit)      { exitPrem = barPrem;     exitIdx = j; break; }
            else if (uStopHit)  { exitPrem = barPrem;     exitIdx = j; break; }
            else if (premStopHit) { exitPrem = premiumStop; exitIdx = j; break; }

            exitPrem = barPrem;   // carry: if we run out of same-day bars, exit here
            exitIdx = j;
        }

        // Exit fill (sell the option) − slippage.
        double exitFill = round2(exitPrem * (1 - slippagePct / 100.0));

        TradeCharges ch = chargesService.calculate(SignalType.BUY, entryFill, exitFill, qty, InstrumentType.INDEX_OPTION);
        double premRisk = entryFill - premiumStop;
        double rMultiple = (premRisk * qty) > 0 ? ch.getNetPnl() / (premRisk * qty) : 0;
        agg.add(ch.getGrossPnl(), ch.getTotalCharges(), ch.getNetPnl(), rMultiple);
        return exitIdx;
    }

    private double premiumClose(Map<LocalDateTime, CandleDTO> optByTs, LocalDateTime ts, double fallback) {
        CandleDTO c = ts == null ? null : optByTs.get(ts);
        return c != null ? c.getClose() : fallback;
    }

    private double yearsToExpiry(LocalDateTime asOf, LocalDate expiry) {
        LocalDateTime expiryClose = expiry.atTime(MARKET_CLOSE);
        long minutes = Math.max(30, ChronoUnit.MINUTES.between(asOf, expiryClose));
        return minutes / (365.0 * 24 * 60);
    }

    // ── shared ────────────────────────────────────────────────

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

    private List<BacktestResultDTO> toResults(Map<StrategyType, Agg> aggs, String mode) {
        List<BacktestResultDTO> out = new ArrayList<BacktestResultDTO>();
        for (Map.Entry<StrategyType, Agg> e : aggs.entrySet()) {
            out.add(e.getValue().toDto(e.getKey().name(), mode));
        }
        out.sort((a, b) -> Double.compare(b.getAvgPnl(), a.getAvgPnl()));
        return out;
    }

    private double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
    private double round2(double v) { return Math.round(v * 100.0) / 100.0; }

    // ── accumulator ───────────────────────────────────────────

    private static final class Agg {
        final java.util.Set<String> symbols = new java.util.HashSet<String>();
        int trades, wins, losses, skipped;
        double gross, charges, net, rSum;
        double grossWins, grossLosses;
        double best = Double.NEGATIVE_INFINITY, worst = Double.POSITIVE_INFINITY;

        void add(double grossPnl, double chargeAmt, double netPnl, double rMultiple) {
            trades++;
            gross += grossPnl; charges += chargeAmt; net += netPnl; rSum += rMultiple;
            if (netPnl > 0) { wins++; grossWins += netPnl; } else { losses++; grossLosses += Math.abs(netPnl); }
            best = Math.max(best, netPnl);
            worst = Math.min(worst, netPnl);
        }

        void skip() { skipped++; }

        BacktestResultDTO toDto(String strategy, String mode) {
            double winRate = trades > 0 ? 100.0 * wins / trades : 0;
            double avgPnl  = trades > 0 ? net / trades : 0;
            double avgR    = trades > 0 ? rSum / trades : 0;
            double pf      = grossLosses > 0 ? grossWins / grossLosses : (grossWins > 0 ? Double.POSITIVE_INFINITY : 0);
            return BacktestResultDTO.builder()
                    .strategy(strategy)
                    .mode(mode)
                    .skipped(skipped)
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
