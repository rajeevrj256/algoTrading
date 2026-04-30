package com.algotrading.service.impl;

import com.algotrading.dto.IntradaySymbolDTO;
import com.algotrading.entity.IntradaySymbolEntity;
import com.algotrading.repository.IntradaySymbolRepository;
import com.algotrading.service.IntradaySymbolService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class IntradaySymbolServiceImpl implements IntradaySymbolService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final IntradaySymbolRepository intradaySymbolRepository;

    @Value("${app.symbols:RELIANCE,TCS,INFY,HDFCBANK,ICICIBANK,AXISBANK,WIPRO,SBIN}")
    private String fallbackSymbolsStr;

    @Value("${symbol-source.cache-ttl-minutes:30}")
    private long cacheTtlMinutes;

    @Value("${symbol-source.fallback-enabled:true}")
    private boolean fallbackEnabled;

    private volatile List<String> cachedSymbols = Collections.emptyList();
    private volatile LocalDateTime cacheExpiresAt;

    @Override
    public List<String> getSymbolsForScan() {
        LocalDateTime now = LocalDateTime.now(IST);
        List<String> snapshot = cachedSymbols;
        if (!snapshot.isEmpty() && cacheExpiresAt != null && cacheExpiresAt.isAfter(now)) {
            return snapshot;
        }
        synchronized (this) {
            snapshot = cachedSymbols;
            if (!snapshot.isEmpty() && cacheExpiresAt != null && cacheExpiresAt.isAfter(now)) {
                return snapshot;
            }
            return reloadCache(now);
        }
    }

    @Override
    public List<IntradaySymbolDTO> getActiveSymbolSnapshot() {
        LocalDateTime now = LocalDateTime.now(IST);
        List<IntradaySymbolEntity> entities = intradaySymbolRepository.findEligibleSymbols(now);
        List<IntradaySymbolDTO> result = new ArrayList<>();
        for (IntradaySymbolEntity entity : entities) {
            result.add(toDto(entity));
        }
        return result;
    }

    @Override
    @Transactional
    public void replaceSymbols(List<IntradaySymbolDTO> symbols, String source) {
        intradaySymbolRepository.deleteAll();

        LocalDateTime now = LocalDateTime.now(IST);
        List<IntradaySymbolEntity> entities = new ArrayList<>();
        for (IntradaySymbolDTO dto : symbols) {
            if (dto == null || dto.getSymbol() == null || dto.getSymbol().trim().isEmpty()) {
                continue;
            }

            String resolvedSource = source != null && !source.trim().isEmpty()
                    ? source.trim()
                    : dto.getSource();

            entities.add(IntradaySymbolEntity.builder()
                    .symbol(dto.getSymbol().trim().toUpperCase())
                    .source(resolvedSource)
                    .score(dto.getScore())
                    .active(dto.getActive() == null ? true : dto.getActive())
                    .notes(dto.getNotes())
                    .selectedAt(dto.getSelectedAt() != null ? dto.getSelectedAt() : now)
                    .validUntil(dto.getValidUntil() != null ? dto.getValidUntil() : now.plusMinutes(cacheTtlMinutes))
                    .build());
        }

        intradaySymbolRepository.saveAll(entities);
        synchronized (this) {
            reloadCache(now);
        }
        log.info("[Symbols] Replaced intraday shortlist with {} symbol(s) from {}",
                entities.size(), resolvedLabel(source));
    }

    @Override
    public void refreshCache() {
        synchronized (this) {
            List<String> refreshed = reloadCache(LocalDateTime.now(IST));
            log.info("[Symbols] Cache refreshed — {} symbol(s)", refreshed.size());
        }
    }

    @Scheduled(
            fixedRateString = "${symbol-source.refresh-interval-ms:1800000}",
            initialDelayString = "${symbol-source.refresh-initial-delay-ms:10000}"
    )
    public void scheduledRefresh() {
        refreshCache();
    }

    private List<String> reloadCache(LocalDateTime now) {
        List<IntradaySymbolEntity> entities = intradaySymbolRepository.findEligibleSymbols(now);
        Set<String> resolved = new LinkedHashSet<>();
        for (IntradaySymbolEntity entity : entities) {
            if (entity.getSymbol() != null && !entity.getSymbol().trim().isEmpty()) {
                resolved.add(entity.getSymbol().trim().toUpperCase());
            }
        }

        if (resolved.isEmpty() && fallbackEnabled) {
            resolved.addAll(parseFallbackSymbols());
            log.warn("[Symbols] No active DB shortlist found — using fallback app.symbols list");
        }

        cachedSymbols = Collections.unmodifiableList(new ArrayList<>(resolved));
        cacheExpiresAt = now.plusMinutes(cacheTtlMinutes);
        return cachedSymbols;
    }

    private List<String> parseFallbackSymbols() {
        if (fallbackSymbolsStr == null || fallbackSymbolsStr.trim().isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> resolved = new LinkedHashSet<>();
        for (String raw : fallbackSymbolsStr.split(",")) {
            String symbol = raw.trim().toUpperCase();
            if (!symbol.isEmpty()) {
                resolved.add(symbol);
            }
        }
        return new ArrayList<>(resolved);
    }

    private IntradaySymbolDTO toDto(IntradaySymbolEntity entity) {
        return IntradaySymbolDTO.builder()
                .symbol(entity.getSymbol())
                .source(entity.getSource())
                .score(entity.getScore())
                .active(entity.isActive())
                .notes(entity.getNotes())
                .selectedAt(entity.getSelectedAt())
                .validUntil(entity.getValidUntil())
                .build();
    }

    private String resolvedLabel(String source) {
        return source != null && !source.trim().isEmpty() ? source.trim() : "external-updater";
    }
}
