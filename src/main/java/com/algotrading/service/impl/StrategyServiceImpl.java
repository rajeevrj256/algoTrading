package com.algotrading.service.impl;

import com.algotrading.dto.CandleDTO;
import com.algotrading.dto.TradeSignalDTO;
import com.algotrading.entity.StrategyConfigEntity;
import com.algotrading.enums.StrategyType;
import com.algotrading.repository.StrategyConfigRepository;
import com.algotrading.service.StrategyService;
import com.algotrading.service.TradingStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * StrategyServiceImpl — implements StrategyService.
 *
 * Spring auto-injects all TradingStrategy beans. The set of strategies that
 * actually run in the live scan is sourced from the strategy_config table:
 *   - loaded ONCE into memory at startup (one DB hit),
 *   - read lock-free on every scan (volatile reference),
 *   - updated at runtime via the API (toggle persists to DB + swaps the set).
 *
 * The strategy.enabled CSV is only a fallback when the table is empty/unreadable.
 */
@Slf4j
@Service
public class StrategyServiceImpl implements StrategyService {

    private final List<TradingStrategy> strategies;
    private final StrategyConfigRepository configRepository;
    private final String enabledCsvFallback;

    /** Live enabled set. Replaced atomically on refresh/toggle; read lock-free. */
    private volatile Set<StrategyType> enabled;

    public StrategyServiceImpl(List<TradingStrategy> strategies,
                               StrategyConfigRepository configRepository,
                               @Value("${strategy.enabled:ORB,VWAP_MR,EMA_CROSS,SUPERTREND,GAP_GO}") String enabledCsv) {
        this.strategies = strategies;
        this.configRepository = configRepository;
        this.enabledCsvFallback = enabledCsv;
        this.enabled = parseCsv(enabledCsv);   // provisional until DB load in @PostConstruct
        log.info("[Strategy] Loaded {} strategies: {}",
                strategies.size(),
                strategies.stream().map(s -> s.getType().name()).collect(Collectors.joining(", ")));
    }

    /** One DB hit at startup to populate the in-memory enabled set. */
    @PostConstruct
    public void init() {
        refreshEnabledStrategies();
    }

    @Override
    public synchronized void refreshEnabledStrategies() {
        try {
            List<StrategyConfigEntity> rows = configRepository.findAll();
            if (rows.isEmpty()) {
                log.warn("[Strategy] strategy_config is empty — using config fallback '{}'", enabledCsvFallback);
                this.enabled = parseCsv(enabledCsvFallback);
            } else {
                Set<StrategyType> set = EnumSet.noneOf(StrategyType.class);
                for (StrategyConfigEntity row : rows) {
                    if (!row.isEnabled()) continue;
                    StrategyType type = parseType(row.getStrategy());
                    if (type != null) set.add(type);
                }
                this.enabled = set;
            }
            log.info("[Strategy] Active strategies (from DB): {}", names(this.enabled));
        } catch (Exception e) {
            log.error("[Strategy] Failed to load strategy_config — keeping current set {} : {}",
                    names(this.enabled), e.getMessage());
        }
    }

    @Override
    public synchronized void setStrategyEnabled(StrategyType type, boolean isEnabled) {
        StrategyConfigEntity row = configRepository.findById(type.name())
                .orElseGet(() -> StrategyConfigEntity.builder().strategy(type.name()).build());
        row.setEnabled(isEnabled);
        configRepository.save(row);
        refreshEnabledStrategies();   // rebuild memory from DB → stays consistent
        log.info("[Strategy] {} {} at runtime", type, isEnabled ? "ENABLED" : "DISABLED");
    }

    @Override
    public Map<String, Boolean> getStrategyStatus() {
        Set<StrategyType> active = this.enabled;
        Map<String, Boolean> status = new LinkedHashMap<>();
        for (TradingStrategy s : strategies) {
            status.put(s.getType().name(), active.contains(s.getType()));
        }
        return status;
    }

    @Override
    public Optional<TradeSignalDTO> runStrategies(String symbol, List<CandleDTO> candles) {
        Set<StrategyType> active = this.enabled;   // single volatile read
        for (TradingStrategy s : strategies) {
            if (!active.contains(s.getType())) {
                continue;   // disabled via strategy_config
            }
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
        // Manual single-strategy run (API/testing) is NOT gated by enabled state.
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

    // ── helpers ───────────────────────────────────────────────

    private Set<StrategyType> parseCsv(String csv) {
        Set<StrategyType> set = EnumSet.noneOf(StrategyType.class);
        if (csv == null) return set;
        for (String token : csv.split(",")) {
            StrategyType type = parseType(token.trim());
            if (type != null) set.add(type);
        }
        return set;
    }

    private StrategyType parseType(String name) {
        if (name == null || name.isEmpty()) return null;
        try {
            return StrategyType.valueOf(name);
        } catch (IllegalArgumentException e) {
            log.warn("[Strategy] Unknown strategy name '{}' — ignored", name);
            return null;
        }
    }

    private String names(Set<StrategyType> set) {
        return set.isEmpty() ? "(none)" : set.stream().map(Enum::name).collect(Collectors.joining(", "));
    }
}
