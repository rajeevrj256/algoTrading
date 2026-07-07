package com.algotrading.service.impl;

import com.algotrading.dto.BacktestResultDTO;
import com.algotrading.entity.BacktestFnoTradeLogEntity;
import com.algotrading.entity.BacktestResultEntity;
import com.algotrading.entity.BacktestRunEntity;
import com.algotrading.entity.BacktestTradeLogEntity;
import com.algotrading.enums.StrategyType;
import com.algotrading.repository.BacktestFnoTradeLogRepository;
import com.algotrading.repository.BacktestResultRepository;
import com.algotrading.repository.BacktestRunRepository;
import com.algotrading.repository.BacktestTradeLogRepository;
import com.algotrading.service.BacktestPersistenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Persists a backtest run (header + per-strategy results + every simulated trade).
 * All writes happen in one transaction; failures are logged and swallowed so the
 * backtest still returns its result.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BacktestPersistenceServiceImpl implements BacktestPersistenceService {

    private final BacktestRunRepository runRepository;
    private final BacktestResultRepository resultRepository;
    private final BacktestTradeLogRepository tradeRepository;
    private final BacktestFnoTradeLogRepository fnoTradeRepository;

    @Override
    @Transactional
    public Long save(String mode, String symbols, StrategyType strategyFilter, int windowDays,
                     List<BacktestResultDTO> results,
                     List<BacktestTradeLogEntity> equityTrades,
                     List<BacktestFnoTradeLogEntity> fnoTrades) {
        List<BacktestResultDTO> res = results != null ? results : Collections.<BacktestResultDTO>emptyList();
        List<BacktestTradeLogEntity> eqTrades = equityTrades != null ? equityTrades : Collections.<BacktestTradeLogEntity>emptyList();
        List<BacktestFnoTradeLogEntity> fnTrades = fnoTrades != null ? fnoTrades : Collections.<BacktestFnoTradeLogEntity>emptyList();
        try {
            double gross = 0, charges = 0, net = 0;
            int trades = 0;
            for (BacktestResultDTO r : res) {
                gross += r.getGrossPnl();
                charges += r.getTotalCharges();
                net += r.getNetPnl();
                trades += r.getTrades();
            }

            BacktestRunEntity run = runRepository.save(BacktestRunEntity.builder()
                    .mode(mode)
                    .symbols(symbols)
                    .strategyFilter(strategyFilter != null ? strategyFilter.name() : null)
                    .windowDays(windowDays)
                    .totalStrategies(res.size())
                    .totalTrades(trades)
                    .grossPnl(round2(gross))
                    .totalCharges(round2(charges))
                    .netPnl(round2(net))
                    .build());
            Long runId = run.getId();

            List<BacktestResultEntity> resultEntities = new ArrayList<BacktestResultEntity>();
            for (BacktestResultDTO r : res) {
                resultEntities.add(BacktestResultEntity.builder()
                        .runId(runId)
                        .strategy(r.getStrategy())
                        .mode(r.getMode())
                        .skipped(r.getSkipped())
                        .symbols(r.getSymbols())
                        .trades(r.getTrades())
                        .wins(r.getWins())
                        .losses(r.getLosses())
                        .winRate(r.getWinRate())
                        .grossPnl(r.getGrossPnl())
                        .totalCharges(r.getTotalCharges())
                        .netPnl(r.getNetPnl())
                        .avgPnl(r.getAvgPnl())
                        .avgR(r.getAvgR())
                        .profitFactor(sanitize(r.getProfitFactor()))
                        .bestTrade(r.getBestTrade())
                        .worstTrade(r.getWorstTrade())
                        .build());
            }
            resultRepository.saveAll(resultEntities);

            for (BacktestTradeLogEntity t : eqTrades) t.setRunId(runId);
            tradeRepository.saveAll(eqTrades);

            for (BacktestFnoTradeLogEntity t : fnTrades) t.setRunId(runId);
            fnoTradeRepository.saveAll(fnTrades);

            log.info("[Backtest] persisted run #{} ({}) — {} result(s), {} equity + {} fno trade(s)",
                    runId, mode, resultEntities.size(), eqTrades.size(), fnTrades.size());
            return runId;
        } catch (Exception e) {
            log.error("[Backtest] persistence failed for {} run ({}): {}", mode, symbols, e.getMessage());
            return null;
        }
    }

    /** profitFactor can be +Infinity (all wins, no losses) — DOUBLE can't store it. */
    private double sanitize(double v) {
        return (Double.isInfinite(v) || Double.isNaN(v)) ? 0.0 : v;
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
