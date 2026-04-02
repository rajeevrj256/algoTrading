package com.algotrading.service.impl;

import com.algotrading.dto.DailySummaryDTO;
import com.algotrading.dto.PositionDTO;
import com.algotrading.entity.DailySummaryEntity;
import com.algotrading.entity.OpenPositionEntity;
import com.algotrading.entity.TradeLogEntity;
import com.algotrading.repository.DailySummaryRepository;
import com.algotrading.repository.OpenPositionRepository;
import com.algotrading.repository.TradeLogRepository;
import com.algotrading.service.SheetsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * PostgresSheetsServiceImpl — implements SheetsService using PostgreSQL.
 *
 * Replaces GoogleSheetsServiceImpl. Persists trade data to three tables:
 *   trade_log      — one row per closed trade
 *   open_position  — current open positions (cleared and refreshed each scan)
 *   daily_summary  — one row per trading day
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostgresSheetsServiceImpl implements SheetsService {

    private final TradeLogRepository tradeLogRepository;
    private final OpenPositionRepository openPositionRepository;
    private final DailySummaryRepository dailySummaryRepository;

    @Value("${spring.datasource.url:jdbc:postgresql://localhost:5432/algo_trading}")
    private String datasourceUrl;

    @Override
    @Transactional
    public void logTrade(PositionDTO pos) {
        try {
            double riskAmt   = Math.abs(pos.getEntryPrice() - pos.getStopLoss()) * pos.getQuantity();
            double rewardAmt = Math.abs(pos.getTarget() - pos.getEntryPrice()) * pos.getQuantity();
            double rr        = riskAmt > 0 ? r2(rewardAmt / riskAmt) : 0;
            String held      = pos.getExitTime() != null && pos.getEntryTime() != null
                    ? Duration.between(pos.getEntryTime(), pos.getExitTime()).toString() : "N/A";

            TradeLogEntity entity = TradeLogEntity.builder()
                    .tradeDate(pos.getEntryTime() != null ? pos.getEntryTime().toLocalDate() : null)
                    .tradeTime(pos.getEntryTime() != null ? pos.getEntryTime().toLocalTime() : null)
                    .symbol(pos.getSymbol())
                    .side(pos.getSignal().name())
                    .strategy(pos.getStrategy() != null ? pos.getStrategy().name() : null)
                    .entryPrice(pos.getEntryPrice())
                    .exitPrice(pos.getExitPrice())
                    .stopLoss(pos.getStopLoss())
                    .target(pos.getTarget())
                    .quantity(pos.getQuantity())
                    .riskAmount(r2(riskAmt))
                    .rewardAmount(r2(rewardAmt))
                    .pnl(r2(pos.getPnl()))
                    .pnlPct(r2(pos.getPnlPct()))
                    .riskReward(rr)
                    .exitReason(pos.getExitReason())
                    .holdDuration(held)
                    .signalReason(pos.getSignalReason())
                    .indRsi(pos.getIndRsi())
                    .indEmaGap(pos.getIndEmaGap())
                    .indVwapDev(pos.getIndVwapDev())
                    .indVolRatio(pos.getIndVolRatio())
                    .indAtr(pos.getIndAtr())
                    .indExtra(pos.getIndExtra())
                    .whyFull(pos.getWhyFull())
                    .status("CLOSED")
                    .build();

            tradeLogRepository.save(entity);
            log.info("[DB] Trade logged id={} | {} P&L=₹{}", entity.getId(), pos.getSymbol(), r2(pos.getPnl()));
        } catch (Exception e) {
            log.error("[DB] logTrade failed: {}", e.getMessage());
        }
    }

    @Override
    @Transactional
    public void updateOpenPositions(List<PositionDTO> openPositions) {
        try {
            openPositionRepository.deleteAll();

            if (openPositions.isEmpty()) return;

            for (PositionDTO p : openPositions) {
                OpenPositionEntity entity = OpenPositionEntity.builder()
                        .symbol(p.getSymbol())
                        .side(p.getSignal().name())
                        .strategy(p.getStrategy() != null ? p.getStrategy().name() : null)
                        .entryPrice(p.getEntryPrice())
                        .stopLoss(p.getStopLoss())
                        .target(p.getTarget())
                        .quantity(p.getQuantity())
                        .entryTime(p.getEntryTime())
                        .signalReason(p.getSignalReason())
                        .build();
                openPositionRepository.save(entity);
            }
            log.info("[DB] Open positions updated — {}", openPositions.size());
        } catch (Exception e) {
            log.error("[DB] updateOpenPositions failed: {}", e.getMessage());
        }
    }

    @Override
    @Transactional
    public void updateDailySummary(DailySummaryDTO s) {
        try {
            Optional<DailySummaryEntity> existing = dailySummaryRepository.findBySummaryDate(s.getDate());

            DailySummaryEntity entity;
            if (existing.isPresent()) {
                entity = existing.get();
            } else {
                entity = new DailySummaryEntity();
                entity.setSummaryDate(s.getDate());
            }

            entity.setStrategy(s.getStrategy());
            entity.setTrades(s.getTrades());
            entity.setWins(s.getWins());
            entity.setLosses(s.getLosses());
            entity.setWinRate(r2(s.getWinRate()));
            entity.setTotalPnl(r2(s.getTotalPnl()));
            entity.setBestTrade(r2(s.getBestTrade()));
            entity.setWorstTrade(r2(s.getWorstTrade()));
            entity.setAvgPnl(r2(s.getAvgPnl()));
            entity.setCircuitTripped(s.isCircuitTripped());

            dailySummaryRepository.save(entity);
            log.info("[DB] Daily summary updated P&L=₹{}", r2(s.getTotalPnl()));
        } catch (Exception e) {
            log.error("[DB] updateDailySummary failed: {}", e.getMessage());
        }
    }

    @Override
    public boolean isConnected() {
        try {
            tradeLogRepository.count();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String getSheetUrl() {
        return datasourceUrl;
    }

    private double r2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
