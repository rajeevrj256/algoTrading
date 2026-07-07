package com.algotrading.service.impl;

import com.algotrading.dto.DailySummaryDTO;
import com.algotrading.dto.PositionDTO;
import com.algotrading.entity.DailySummaryEntity;
import com.algotrading.entity.FnoOpenPositionEntity;
import com.algotrading.entity.FnoTradeLogEntity;
import com.algotrading.entity.OpenPositionEntity;
import com.algotrading.entity.TradeLogEntity;
import com.algotrading.enums.PositionStatus;
import com.algotrading.enums.SignalType;
import com.algotrading.enums.StrategyType;
import com.algotrading.repository.DailySummaryRepository;
import com.algotrading.repository.FnoOpenPositionRepository;
import com.algotrading.repository.FnoTradeLogRepository;
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
    private final FnoTradeLogRepository fnoTradeLogRepository;
    private final FnoOpenPositionRepository fnoOpenPositionRepository;

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

            Long id;
            String table;
            if (pos.isOption()) {
                // F&O trade → fno_trade_log
                FnoTradeLogEntity entity = FnoTradeLogEntity.builder()
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
                        .instrumentType(pos.getInstrumentType() != null ? pos.getInstrumentType().name() : null)
                        .underlying(limit(pos.getUnderlying(), 50))
                        .optionType(pos.getOptionType() != null ? pos.getOptionType().name() : null)
                        .strike(pos.getStrike())
                        .expiry(pos.getExpiry())
                        .lotSize(pos.getLotSize())
                        .lots(pos.getLots())
                        .status("CLOSED")
                        .build();
                fnoTradeLogRepository.save(entity);
                id = entity.getId();
                table = "fno_trade_log";
            } else {
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
                        .instrumentType(pos.getInstrumentType() != null ? pos.getInstrumentType().name() : null)
                        .underlying(limit(pos.getUnderlying(), 50))
                        .optionType(pos.getOptionType() != null ? pos.getOptionType().name() : null)
                        .strike(pos.getStrike())
                        .expiry(pos.getExpiry())
                        .lotSize(pos.getLotSize())
                        .lots(pos.getLots())
                        .status("CLOSED")
                        .build();
                tradeLogRepository.save(entity);
                id = entity.getId();
                table = "trade_log";
            }

            log.info("[DB] Trade logged {}#{} | {} Gross=₹{} Charges=₹{} Net=₹{}",
                    table, id, pos.getSymbol(),
                    r2(pos.getGrossPnl()), r2(pos.getCharges()), r2(pos.getPnl()));
        } catch (Exception e) {
            log.error("[DB] logTrade failed: {}", e.getMessage());
        }
    }

    @Override
    @Transactional
    public void updateOpenPositions(List<PositionDTO> openPositions) {
        try {
            // Both tables are cleared and refreshed each scan (snapshot semantics).
            openPositionRepository.deleteAll();
            fnoOpenPositionRepository.deleteAll();

            if (openPositions.isEmpty()) return;

            int equity = 0, fno = 0;
            for (PositionDTO p : openPositions) {
                if (p.isOption()) {
                    fnoOpenPositionRepository.save(toFnoOpenEntity(p));
                    fno++;
                } else {
                    openPositionRepository.save(toEquityOpenEntity(p));
                    equity++;
                }
            }
            log.info("[DB] Open positions updated — {} equity, {} F&O", equity, fno);
        } catch (Exception e) {
            log.error("[DB] updateOpenPositions failed: {}", e.getMessage());
        }
    }

    private OpenPositionEntity toEquityOpenEntity(PositionDTO p) {
        return OpenPositionEntity.builder()
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
                .initialStop(p.getInitialStop())
                .peakPrice(p.getPeakPrice())
                .instrumentType(p.getInstrumentType() != null ? p.getInstrumentType().name() : null)
                .underlying(limit(p.getUnderlying(), 50))
                .optionType(p.getOptionType() != null ? p.getOptionType().name() : null)
                .strike(p.getStrike())
                .expiry(p.getExpiry())
                .lotSize(p.getLotSize())
                .lots(p.getLots())
                .underlyingEntry(p.getUnderlyingEntry())
                .underlyingStop(p.getUnderlyingStop())
                .underlyingInitialStop(p.getUnderlyingInitialStop())
                .underlyingTarget(p.getUnderlyingTarget())
                .underlyingPeak(p.getUnderlyingPeak())
                .build();
    }

    private FnoOpenPositionEntity toFnoOpenEntity(PositionDTO p) {
        return FnoOpenPositionEntity.builder()
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
                .initialStop(p.getInitialStop())
                .peakPrice(p.getPeakPrice())
                .instrumentType(p.getInstrumentType() != null ? p.getInstrumentType().name() : null)
                .underlying(limit(p.getUnderlying(), 50))
                .optionType(p.getOptionType() != null ? p.getOptionType().name() : null)
                .strike(p.getStrike())
                .expiry(p.getExpiry())
                .lotSize(p.getLotSize())
                .lots(p.getLots())
                .underlyingEntry(p.getUnderlyingEntry())
                .underlyingStop(p.getUnderlyingStop())
                .underlyingInitialStop(p.getUnderlyingInitialStop())
                .underlyingTarget(p.getUnderlyingTarget())
                .underlyingPeak(p.getUnderlyingPeak())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public List<PositionDTO> loadOpenPositions() {
        try {
            List<PositionDTO> all = new java.util.ArrayList<PositionDTO>();
            for (OpenPositionEntity e : openPositionRepository.findAll()) {
                all.add(toPositionDto(e));
            }
            for (FnoOpenPositionEntity e : fnoOpenPositionRepository.findAll()) {
                all.add(toPositionDtoFno(e));
            }
            return all;
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
                .whyFull(entity.getWhyFull())
                .initialStop(entity.getInitialStop() != 0 ? entity.getInitialStop() : entity.getStopLoss())
                .peakPrice(entity.getPeakPrice() != 0 ? entity.getPeakPrice() : entity.getEntryPrice())
                .underlying(entity.getUnderlying())
                .strike(entity.getStrike())
                .expiry(entity.getExpiry())
                .lotSize(entity.getLotSize())
                .lots(entity.getLots())
                .underlyingEntry(entity.getUnderlyingEntry())
                .underlyingStop(entity.getUnderlyingStop())
                .underlyingInitialStop(entity.getUnderlyingInitialStop())
                .underlyingTarget(entity.getUnderlyingTarget())
                .underlyingPeak(entity.getUnderlyingPeak());

        if (entity.getInstrumentType() != null) {
            try {
                builder.instrumentType(com.algotrading.enums.InstrumentType.valueOf(entity.getInstrumentType()));
            } catch (IllegalArgumentException ignored) {
                log.warn("[DB] Ignoring invalid instrument_type for {}: {}", entity.getSymbol(), entity.getInstrumentType());
            }
        }
        if (entity.getOptionType() != null) {
            try {
                builder.optionType(com.algotrading.enums.OptionType.valueOf(entity.getOptionType()));
            } catch (IllegalArgumentException ignored) {
                log.warn("[DB] Ignoring invalid option_type for {}: {}", entity.getSymbol(), entity.getOptionType());
            }
        }
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

    private PositionDTO toPositionDtoFno(FnoOpenPositionEntity entity) {
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
                .whyFull(entity.getWhyFull())
                .initialStop(entity.getInitialStop() != 0 ? entity.getInitialStop() : entity.getStopLoss())
                .peakPrice(entity.getPeakPrice() != 0 ? entity.getPeakPrice() : entity.getEntryPrice())
                .underlying(entity.getUnderlying())
                .strike(entity.getStrike())
                .expiry(entity.getExpiry())
                .lotSize(entity.getLotSize())
                .lots(entity.getLots())
                .underlyingEntry(entity.getUnderlyingEntry())
                .underlyingStop(entity.getUnderlyingStop())
                .underlyingInitialStop(entity.getUnderlyingInitialStop())
                .underlyingTarget(entity.getUnderlyingTarget())
                .underlyingPeak(entity.getUnderlyingPeak());

        if (entity.getInstrumentType() != null) {
            try {
                builder.instrumentType(com.algotrading.enums.InstrumentType.valueOf(entity.getInstrumentType()));
            } catch (IllegalArgumentException ignored) {
                log.warn("[DB] Ignoring invalid fno instrument_type for {}: {}", entity.getSymbol(), entity.getInstrumentType());
            }
        }
        if (entity.getOptionType() != null) {
            try {
                builder.optionType(com.algotrading.enums.OptionType.valueOf(entity.getOptionType()));
            } catch (IllegalArgumentException ignored) {
                log.warn("[DB] Ignoring invalid fno option_type for {}: {}", entity.getSymbol(), entity.getOptionType());
            }
        }
        if (entity.getSide() != null) {
            try {
                builder.signal(SignalType.valueOf(entity.getSide()));
            } catch (IllegalArgumentException ignored) {
                log.warn("[DB] Skipping invalid fno_open_position side for {}: {}", entity.getSymbol(), entity.getSide());
            }
        }
        if (entity.getStrategy() != null) {
            try {
                builder.strategy(StrategyType.valueOf(entity.getStrategy()));
            } catch (IllegalArgumentException ignored) {
                log.warn("[DB] Ignoring invalid fno_open_position strategy for {}: {}", entity.getSymbol(), entity.getStrategy());
            }
        }
        return builder.build();
    }
}
