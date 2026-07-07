package com.algotrading.service.impl;

import com.algotrading.dto.CandleDTO;
import com.algotrading.dto.TradeSignalDTO;
import com.algotrading.entity.StrategyConfigEntity;
import com.algotrading.enums.Segment;
import com.algotrading.enums.StrategyType;
import com.algotrading.repository.StrategyConfigRepository;
import com.algotrading.service.StrategyService;
import com.algotrading.service.TradingStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.EnumMap;
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

    /** Per-strategy engine assignment (EQUITY/FNO/BOTH). Replaced atomically on refresh. */
    private volatile Map<StrategyType, Segment> segmentByType = new EnumMap<StrategyType, Segment>(StrategyType.class);

    public StrategyServiceImpl(List<TradingStrategy> strategies,
                               StrategyConfigRepository configRepository,
                               @Value("${strategy.enabled:VWAP_TREND,EMA_PULLBACK,ORB_REFINED,INDEX_TREND}") String enabledCsv) {
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
                this.segmentByType = defaultSegments();
            } else {
                Set<StrategyType> set = EnumSet.noneOf(StrategyType.class);
                Map<StrategyType, Segment> segments = new EnumMap<StrategyType, Segment>(StrategyType.class);
                for (StrategyConfigEntity row : rows) {
                    StrategyType type = parseType(row.getStrategy());
                    if (type == null) continue;
                    if (row.isEnabled()) set.add(type);
                    segments.put(type, parseSegment(row.getSegment()));
                }
                this.enabled = set;
                this.segmentByType = segments;
            }
            log.info("[Strategy] Active strategies (from DB): {} | segments: {}",
                    names(this.enabled), this.segmentByType);
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

    /**
     * Confluence-aware runner (replaces the old first-signal-wins):
     *   - ALL enabled strategies run;
     *   - signals in OPPOSITE directions cancel each other (that disagreement is
     *     exactly what chop looks like — stay flat);
     *   - 2+ strategies agreeing = highest-confidence signal with a confluence
     *     confidence bonus;
     *   - a single signal passes through unchanged.
     */
    @Override
    public Optional<TradeSignalDTO> runStrategies(String symbol, List<CandleDTO> candles) {
        return runFiltered(symbol, candles, this.enabled);
    }

    @Override
    public Optional<TradeSignalDTO> runStrategies(String symbol, List<CandleDTO> candles, Segment segment) {
        Set<StrategyType> active = this.enabled;
        Map<StrategyType, Segment> segments = this.segmentByType;
        Set<StrategyType> forSegment = EnumSet.noneOf(StrategyType.class);
        for (StrategyType t : active) {
            if (segmentMatches(segments.get(t), segment)) forSegment.add(t);
        }
        if (forSegment.isEmpty()) {
            log.debug("[Strategy] {} — no enabled {} strategy", symbol, segment);
            return Optional.empty();
        }
        return runFiltered(symbol, candles, forSegment);
    }

    @Override
    public Set<StrategyType> strategiesForSegment(Segment segment) {
        Map<StrategyType, Segment> segments = this.segmentByType;
        Set<StrategyType> result = EnumSet.noneOf(StrategyType.class);
        for (TradingStrategy s : strategies) {
            Segment seg = segments.getOrDefault(s.getType(), defaultSegmentFor(s.getType()));
            if (segmentMatches(seg, segment)) result.add(s.getType());
        }
        return result;
    }

    private Optional<TradeSignalDTO> runFiltered(String symbol, List<CandleDTO> candles, Set<StrategyType> active) {
        List<TradeSignalDTO> fired = new java.util.ArrayList<>();

        for (TradingStrategy s : strategies) {
            if (!active.contains(s.getType())) {
                continue;   // disabled via strategy_config or not in this segment
            }
            if (candles.size() < s.minCandles()) {
                log.debug("[Strategy] {} skipped — need {} candles, have {}", s.getType(), s.minCandles(), candles.size());
                continue;
            }
            try {
                Optional<TradeSignalDTO> signal = s.generate(symbol, candles);
                if (signal.isPresent()) {
                    log.info("[Strategy] {} fired for {} — {}", s.getType(), symbol, signal.get().getSignalReason());
                    fired.add(signal.get());
                }
            } catch (Exception e) {
                log.warn("[Strategy] {} error on {}: {}", s.getType(), symbol, e.getMessage());
            }
        }

        if (fired.isEmpty()) return Optional.empty();

        boolean hasBuy = false, hasSell = false;
        for (TradeSignalDTO sig : fired) {
            if (sig.getSignal() == com.algotrading.enums.SignalType.BUY) hasBuy = true;
            else hasSell = true;
        }
        if (hasBuy && hasSell) {
            log.info("[Strategy] {} — conflicting BUY/SELL signals from {} strategies, standing aside (chop)",
                    symbol, fired.size());
            return Optional.empty();
        }

        TradeSignalDTO best = fired.get(0);
        for (TradeSignalDTO sig : fired) {
            if (sig.getConfidence() > best.getConfidence()) best = sig;
        }
        if (fired.size() > 1) {
            best.setConfidence(Math.min(0.95, best.getConfidence() + 0.05 * (fired.size() - 1)));
            best.setSignalReason(best.getSignalReason()
                    + String.format(" [confluence x%d]", fired.size()));
            log.info("[Strategy] {} — {} strategies agree {} → confidence {}",
                    symbol, fired.size(), best.getSignal(), best.getConfidence());
        }
        return Optional.of(best);
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

    private Segment parseSegment(String raw) {
        if (raw == null || raw.trim().isEmpty()) return Segment.EQUITY;
        try {
            return Segment.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("[Strategy] Unknown segment '{}' — defaulting EQUITY", raw);
            return Segment.EQUITY;
        }
    }

    /** requested EQUITY/FNO matches a strategy tagged with that segment OR BOTH. */
    private boolean segmentMatches(Segment configured, Segment requested) {
        Segment c = configured == null ? Segment.EQUITY : configured;
        return c == Segment.BOTH || c == requested;
    }

    /** Fallback when strategy_config is empty: INDEX_TREND → FNO, everything else EQUITY. */
    private Map<StrategyType, Segment> defaultSegments() {
        Map<StrategyType, Segment> m = new EnumMap<StrategyType, Segment>(StrategyType.class);
        for (TradingStrategy s : strategies) {
            m.put(s.getType(), defaultSegmentFor(s.getType()));
        }
        return m;
    }

    private Segment defaultSegmentFor(StrategyType type) {
        return type == StrategyType.INDEX_TREND ? Segment.FNO : Segment.EQUITY;
    }

    private String names(Set<StrategyType> set) {
        return set.isEmpty() ? "(none)" : set.stream().map(Enum::name).collect(Collectors.joining(", "));
    }
}
