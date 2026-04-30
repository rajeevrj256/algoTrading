package com.algotrading.service.impl;

import com.algotrading.dto.DailySummaryDTO;
import com.algotrading.dto.PositionDTO;
import com.algotrading.entity.DailySummaryEntity;
import com.algotrading.entity.OpenPositionEntity;
import com.algotrading.entity.TradeLogEntity;
import com.algotrading.enums.PositionStatus;
import com.algotrading.enums.SignalType;
import com.algotrading.enums.StrategyType;
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
                    ? compactDuration(Duration.between(pos.getEntryTime(), pos.getExitTime())) : "N/A";

            TradeLogEntity entity = TradeLogEntity.builder()
                    .tradeDate(pos.getEntryTime() != null ? pos.getEntryTime().toLocalDate() : null)
                    .tradeTime(pos.getEntryTime() != null ? pos.getEntryTime().toLocalTime() : null)
                    .positionId(limit(pos.getPositionId(), 50))
                    .symbol(limit(pos.getSymbol(), 50))
                    .side(limit(pos.getSignal().name(), 10))
                    .strategy(limit(pos.getStrategy() != null ? pos.getStrategy().name() : null, 30))
                    .entryPrice(pos.getEntryPrice())
                    .exitPrice(pos.getExitPrice())
                    .stopLoss(pos.getStopLoss())
                    .target(pos.getTarget())
                    .quantity(pos.getQuantity())
                    .riskAmount(r2(riskAmt))
                    .rewardAmount(r2(rewardAmt))
                    .charges(r2(pos.getCharges()))
                    .pnl(r2(pos.getPnl()))
                    .pnlPct(r2(pos.getPnlPct()))
                    .riskReward(rr)
                    .exitReason(limit(pos.getExitReason(), 255))
                    .holdDuration(limit(held, 100))
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
            log.info("[DB] Trade logged id={} | {} Gross=₹{} Charges=₹{} Net=₹{}",
                    entity.getId(), pos.getSymbol(),
                    r2(pos.getGrossPnl()), r2(pos.getCharges()), r2(pos.getPnl()));
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
                        .positionId(limit(p.getPositionId(), 50))
                        .symbol(p.getSymbol())
                        .side(p.getSignal().name())
                        .strategy(p.getStrategy() != null ? p.getStrategy().name() : null)
                        .entryPrice(p.getEntryPrice())
                        .stopLoss(p.getStopLoss())
                        .target(p.getTarget())
                        .quantity(p.getQuantity())
                        .entryTime(p.getEntryTime())
                        .signalReason(p.getSignalReason())
                        .indRsi(p.getIndRsi())
                        .indEmaGap(p.getIndEmaGap())
                        .indVwapDev(p.getIndVwapDev())
                        .indVolRatio(p.getIndVolRatio())
                        .indAtr(p.getIndAtr())
                        .indExtra(p.getIndExtra())
                        .whyFull(p.getWhyFull())
                        .build();
                openPositionRepository.save(entity);
            }
            log.info("[DB] Open positions updated — {}", openPositions.size());
        } catch (Exception e) {
            log.error("[DB] updateOpenPositions failed: {}", e.getMessage());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<PositionDTO> loadOpenPositions() {
        try {
            List<OpenPositionEntity> entities = openPositionRepository.findAll();
            return entities.stream()
                    .map(this::toPositionDto)
                    .collect(java.util.stream.Collectors.toList());
        } catch (Exception e) {
            log.error("[DB] loadOpenPositions failed: {}", e.getMessage());
            return java.util.Collections.emptyList();
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

    private String compactDuration(Duration duration) {
        long seconds = Math.abs(duration.getSeconds());
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;

        if (hours > 0) return String.format("%dh %dm %ds", hours, minutes, secs);
        if (minutes > 0) return String.format("%dm %ds", minutes, secs);
        return String.format("%ds", secs);
    }

    private String limit(String value, int max) {
        if (value == null || value.length() <= max) return value;
        return value.substring(0, max);
    }

    private PositionDTO toPositionDto(OpenPositionEntity entity) {
        PositionDTO.PositionDTOBuilder builder = PositionDTO.builder()
                .positionId(limit(entity.getPositionId(), 50))
                .symbol(entity.getSymbol())
                .entryPrice(entity.getEntryPrice())
                .stopLoss(entity.getStopLoss())
                .target(entity.getTarget())
                .quantity(entity.getQuantity())
                .entryTime(entity.getEntryTime())
                .status(PositionStatus.OPEN)
                .signalReason(entity.getSignalReason())
                .indRsi(entity.getIndRsi())
                .indEmaGap(entity.getIndEmaGap())
                .indVwapDev(entity.getIndVwapDev())
                .indVolRatio(entity.getIndVolRatio())
                .indAtr(entity.getIndAtr())
                .indExtra(entity.getIndExtra())
                .whyFull(entity.getWhyFull());

        if (entity.getSide() != null) {
            try {
                builder.signal(SignalType.valueOf(entity.getSide()));
            } catch (IllegalArgumentException ignored) {
                log.warn("[DB] Skipping invalid open_position side for {}: {}", entity.getSymbol(), entity.getSide());
            }
        }
        if (entity.getStrategy() != null) {
            try {
                builder.strategy(StrategyType.valueOf(entity.getStrategy()));
            } catch (IllegalArgumentException ignored) {
                log.warn("[DB] Ignoring invalid open_position strategy for {}: {}", entity.getSymbol(), entity.getStrategy());
            }
        }
        return builder.build();
    }
}
