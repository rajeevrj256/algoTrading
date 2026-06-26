package com.algotrading.service.impl;

import com.algotrading.dto.CandleDTO;
import com.algotrading.entity.CandleHistoryEntity;
import com.algotrading.repository.CandleHistoryRepository;
import com.algotrading.service.CandleHistoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CandleHistoryServiceImpl implements CandleHistoryService {

    private final CandleHistoryRepository repository;

    @Override
    @Transactional
    public void saveAll(String symbol, List<CandleDTO> candles) {
        if (symbol == null || candles == null || candles.isEmpty()) return;
        int saved = 0;
        for (CandleDTO c : candles) {
            if (c == null || c.getTimestamp() == null) continue;
            try {
                repository.upsert(symbol, c.getTimestamp(),
                        c.getOpen(), c.getHigh(), c.getLow(), c.getClose(), c.getVolume());
                saved++;
            } catch (Exception e) {
                log.debug("[CandleHistory] upsert skipped for {} @ {}: {}", symbol, c.getTimestamp(), e.getMessage());
            }
        }
        log.debug("[CandleHistory] {} — upserted {}/{} bars", symbol, saved, candles.size());
    }

    @Override
    public List<CandleDTO> load(String symbol, int limit) {
        List<CandleHistoryEntity> rows = repository.findBySymbolOrderByTsAsc(symbol);
        // Keep only the most-recent `limit`, preserving ascending order.
        int from = limit > 0 && rows.size() > limit ? rows.size() - limit : 0;
        List<CandleDTO> out = new ArrayList<>(rows.size() - from);
        for (int i = from; i < rows.size(); i++) {
            CandleHistoryEntity e = rows.get(i);
            out.add(CandleDTO.builder()
                    .symbol(e.getSymbol()).timestamp(e.getTs())
                    .open(e.getOpen()).high(e.getHigh()).low(e.getLow()).close(e.getClose())
                    .volume(e.getVolume())
                    .build());
        }
        return out;
    }

    @Override
    public long count(String symbol) {
        return repository.countBySymbol(symbol);
    }
}
