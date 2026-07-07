package com.algotrading.service.impl;

import com.algotrading.dto.DailySummaryDTO;
import com.algotrading.dto.RiskValidationDTO;
import com.algotrading.dto.StrategyExpectancyDTO;
import com.algotrading.dto.TradeSignalDTO;
import com.algotrading.entity.FnoTradeLogEntity;
import com.algotrading.entity.TradeLogEntity;
import com.algotrading.model.DailyStats;
import com.algotrading.repository.FnoTradeLogRepository;
import com.algotrading.repository.TradeLogRepository;
import com.algotrading.service.RiskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * RiskServiceImpl — implements RiskService.
 *
 * Holds DailyStats in memory. Resets at 09:00 IST each trading day via @Scheduled.
 * Thread-safe: DailyStats uses AtomicReference/AtomicBoolean internally.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RiskServiceImpl implements RiskService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Value("${risk.capital:100000}")
    private double capital;

    @Value("${risk.daily-target:1000}")
    private double dailyTarget;

    @Value("${risk.daily-max-loss:500}")
    private double dailyMaxLoss;

    @Value("${risk.max-trades-per-day:40}")
    private int maxTradesPerDay;

    @Value("${risk.min-confidence:0.60}")
    private double minConfidence;

    @Value("${risk.min-rr-ratio:1.5}")
    private double minRrRatio;

    /** Options are lot-lumpy: one lot can carry more risk than the 1% equity cap. */
    @Value("${fno.max-risk-per-trade-pct:2.5}")
    private double fnoMaxRiskPct;

    private final TradeLogRepository tradeLogRepository;
    private final FnoTradeLogRepository fnoTradeLogRepository;

    private DailyStats stats = new DailyStats(LocalDate.now(IST).toString());

    @PostConstruct
    public void restoreTodayStats() {
        String today = LocalDate.now(IST).toString();
        stats.reset(today);

        try {
            List<TradeLogEntity> trades = tradeLogRepository.findByTradeDate(LocalDate.now(IST));
            for (TradeLogEntity trade : trades) {
                stats.record(trade.getPnl());
            }
            // F&O trades live in a separate table — include them in today's P&L / circuit.
            List<FnoTradeLogEntity> fnoTrades = fnoTradeLogRepository.findByTradeDate(LocalDate.now(IST));
            for (FnoTradeLogEntity trade : fnoTrades) {
                stats.record(trade.getPnl());
            }

            if (stats.getTotalPnl().get() <= -dailyMaxLoss) {
                stats.getCircuitTripped().set(true);
            }

            if (!trades.isEmpty() || !fnoTrades.isEmpty()) {
                log.info("[Risk] Restored {} equity + {} F&O closed trade(s) for {} | P&L=₹{} | Trades={}",
                        trades.size(), fnoTrades.size(), today,
                        String.format("%.2f", stats.getTotalPnl().get()),
                        stats.getTrades().get());
            }
        } catch (Exception e) {
            log.error("[Risk] Failed to restore daily stats: {}", e.getMessage(), e);
        }
    }

    // ── canTrade ──────────────────────────────────────────────

    @Override
    public RiskValidationDTO canTrade() {
        if (stats.getCircuitTripped().get())
            return denied("Circuit breaker tripped — daily loss limit ₹" + dailyMaxLoss + " hit");
        if (stats.getTrades().get() >= maxTradesPerDay)
            return denied("Max trades per day (" + maxTradesPerDay + ") reached");
        if (stats.getTotalPnl().get() >= dailyTarget)
            return denied("Daily profit target ₹" + dailyTarget + " hit — protecting gains");

        LocalTime now = LocalTime.now(IST);
        if (now.isBefore(LocalTime.of(9, 15)))
            return denied("Market not yet open — before 09:15 IST");
        if (now.isAfter(LocalTime.of(15, 0)))
            return denied("No new trades after 15:00 IST");

        return RiskValidationDTO.builder().approved(true).reason("All risk checks passed").build();
    }

    // ── validateSignal ────────────────────────────────────────

    @Override
    public RiskValidationDTO validateSignal(TradeSignalDTO signal) {
        if (signal.getConfidence() < minConfidence)
            return denied(String.format("Confidence %.2f < minimum %.2f", signal.getConfidence(), minConfidence));
        if (signal.getRrRatio() < minRrRatio)
            return denied(String.format("R:R %.2f < minimum %.2f", signal.getRrRatio(), minRrRatio));
        if (signal.getQuantity() <= 0)
            return denied("Invalid quantity: " + signal.getQuantity());
        if (signal.getEntryPrice() <= 0)
            return denied("Invalid entry price: " + signal.getEntryPrice());

        boolean isOption = signal.getInstrumentType() == com.algotrading.enums.InstrumentType.INDEX_OPTION;
        double riskAmt   = Math.abs(signal.getEntryPrice() - signal.getStopLoss()) * signal.getQuantity();
        double maxAllowedPct = isOption ? fnoMaxRiskPct : 1.0;
        double maxAllowed = capital * maxAllowedPct / 100;
        if (riskAmt > maxAllowed)
            return denied(String.format("Risk ₹%.2f exceeds hard cap ₹%.2f (%.1f%% capital)",
                    riskAmt, maxAllowed, maxAllowedPct));

        return RiskValidationDTO.builder()
                .approved(true).reason("Signal validation passed")
                .riskAmount(r2(riskAmt)).adjustedQuantity(signal.getQuantity()).build();
    }

    // ── recordTrade ───────────────────────────────────────────

    @Override
    public void recordTrade(double pnl) {
        stats.record(pnl);
        log.info("[Risk] Trade recorded P&L=₹{} | DayP&L=₹{} | Trades={} | WR={}%",
                String.format("%.2f", pnl),
                String.format("%.2f", stats.getTotalPnl().get()),
                stats.getTrades().get(),
                String.format("%.0f", stats.getWinRate()));

        if (stats.getTotalPnl().get() <= -dailyMaxLoss && !stats.getCircuitTripped().get()) {
            stats.getCircuitTripped().set(true);
            log.error("[Risk] *** CIRCUIT BREAKER TRIPPED *** Loss ₹{} exceeds ₹{}",
                    String.format("%.2f", Math.abs(stats.getTotalPnl().get())),
                    String.format("%.2f", dailyMaxLoss));
        }
    }

    // ── getDailySummary ───────────────────────────────────────

    @Override
    public DailySummaryDTO getDailySummary() {
        return DailySummaryDTO.builder()
                .date(stats.getDate()).strategy("ALL")
                .trades(stats.getTrades().get())
                .wins(stats.getWins().get())
                .losses(stats.getLosses().get())
                .winRate(r2(stats.getWinRate()))
                .totalPnl(r2(stats.getTotalPnl().get()))
                .bestTrade(r2(stats.getBestTrade().get()))
                .worstTrade(r2(stats.getWorstTrade().get()))
                .avgPnl(r2(stats.getAvgPnl()))
                .circuitTripped(stats.getCircuitTripped().get())
                .build();
    }

    // ── resetDay ──────────────────────────────────────────────

    @Override
    @Scheduled(cron = "0 0 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void resetDay() {
        String today = LocalDate.now(IST).toString();
        log.info("[Risk] Resetting daily stats for {}", today);
        stats.reset(today);
    }

    @Override
    public boolean isCircuitTripped() { return stats.getCircuitTripped().get(); }

    // ── getStrategyExpectancy ─────────────────────────────────

    @Override
    public List<StrategyExpectancyDTO> getStrategyExpectancy(int days) {
        int window = days > 0 ? days : 7;
        List<StrategyExpectancyDTO> result = new ArrayList<>();
        addExpectancyRows(tradeLogRepository.findStrategyExpectancy(window), result);      // equity
        addExpectancyRows(fnoTradeLogRepository.findStrategyExpectancy(window), result);   // F&O
        result.sort((a, b) -> Double.compare(b.getAvgPnl(), a.getAvgPnl()));
        return result;
    }

    private void addExpectancyRows(List<Object[]> rows, List<StrategyExpectancyDTO> result) {
        for (Object[] row : rows) {
            long trades = lng(row[1]);
            long wins   = lng(row[2]);
            double winRate = trades > 0 ? r2(100.0 * wins / trades) : 0;
            result.add(StrategyExpectancyDTO.builder()
                    .strategy(str(row[0]))
                    .trades(trades)
                    .wins(wins)
                    .losses(lng(row[3]))
                    .winRate(winRate)
                    .totalPnl(r2(dbl(row[4])))
                    .avgPnl(r2(dbl(row[5])))
                    .totalCharges(r2(dbl(row[6])))
                    .avgR(Math.round(dbl(row[7]) * 1000.0) / 1000.0)
                    .bestTrade(r2(dbl(row[8])))
                    .worstTrade(r2(dbl(row[9])))
                    .build());
        }
    }

    private static String str(Object o) { return o == null ? "UNKNOWN" : o.toString(); }
    private static long   lng(Object o) { return o == null ? 0L : ((Number) o).longValue(); }
    private static double dbl(Object o) { return o == null ? 0.0 : ((Number) o).doubleValue(); }

    private RiskValidationDTO denied(String reason) {
        log.warn("[Risk] DENIED — {}", reason);
        return RiskValidationDTO.builder().approved(false).reason(reason).build();
    }

    private double r2(double v) { return Math.round(v * 100.0) / 100.0; }
}
