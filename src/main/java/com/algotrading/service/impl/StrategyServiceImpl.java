package com.algotrading.service.impl;

import com.algotrading.dto.CandleDTO;
import com.algotrading.dto.TradeSignalDTO;
import com.algotrading.enums.StrategyType;
import com.algotrading.service.StrategyService;
import com.algotrading.service.TradingStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * StrategyServiceImpl — implements StrategyService.
 *
 * Spring auto-injects all TradingStrategy beans (ORB, ORB_RETEST, VWAP_MR,
 * EMA_CROSS, SUPERTREND, GAP_GO) into the strategies list. Strategies run in
 * the order Spring resolves them; the first one that fires wins for the scan tick.
 */
@Slf4j
@Service
public class StrategyServiceImpl implements StrategyService {

    private final List<TradingStrategy> strategies;

    public StrategyServiceImpl(List<TradingStrategy> strategies) {
        this.strategies = strategies;
        log.info("[Strategy] Loaded {} strategies: {}",
                strategies.size(),
                strategies.stream().map(s -> s.getType().name()).collect(Collectors.joining(", ")));
    }

    @Override
    public Optional<TradeSignalDTO> runStrategies(String symbol, List<CandleDTO> candles) {
        for (TradingStrategy s : strategies) {
            if (candles.size() < s.minCandles()) {
                log.debug("[Strategy] {} skipped — need {} candles, have {}", s.getType(), s.minCandles(), candles.size());
                continue;
            }
            try {
                Optional<TradeSignalDTO> signal = s.generate(symbol, candles);
                if (signal.isPresent()) {
                    log.info("[Strategy] {} fired for {} — {}", s.getType(), symbol, signal.get().getSignalReason());
                    return signal;
                }
            } catch (Exception e) {
                log.warn("[Strategy] {} error on {}: {}", s.getType(), symbol, e.getMessage());
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<TradeSignalDTO> runStrategy(StrategyType type, String symbol, List<CandleDTO> candles) {
        return strategies.stream()
                .filter(s -> s.getType() == type)
                .findFirst()
                .flatMap(s -> {
                    try { return s.generate(symbol, candles); }
                    catch (Exception e) {
                        log.warn("[Strategy] {} error on {}: {}", type, symbol, e.getMessage());
                        return Optional.empty();
                    }
                });
    }

    @Override
    public List<String> listStrategies() {
        return strategies.stream().map(s -> s.getType().name()).collect(Collectors.toList());
    }
}
