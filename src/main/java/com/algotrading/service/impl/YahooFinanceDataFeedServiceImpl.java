package com.algotrading.service.impl;

import com.algotrading.dto.CandleDTO;
import com.algotrading.service.DataFeedService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * YahooFinanceDataFeedServiceImpl — implements DataFeedService.
 *
 * Uses the Yahoo Finance v8 chart API to fetch 5-minute NSE candles.
 * Maintains an in-memory TTL cache (default 60 s) to avoid hammering the API.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class YahooFinanceDataFeedServiceImpl implements DataFeedService {

    private static final String YAHOO_URL =
            "https://query1.finance.yahoo.com/v8/finance/chart/%s.NS?interval=5m&range=5d";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Value("${datafeed.cache.ttl-seconds:60}")
    private int ttlSeconds;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    private final Map<String, List<CandleDTO>> cache     = new ConcurrentHashMap<>();
    private final Map<String, LocalDateTime>   cacheTime = new ConcurrentHashMap<>();

    // ── DataFeedService impl ──────────────────────────────────

    @Override
    public List<CandleDTO> getCandles(String symbol, int count) {
        if (isFresh(symbol)) {
            List<CandleDTO> cached = cache.get(symbol);
            if (cached != null) return tail(cached, count);
        }
        List<CandleDTO> fetched = fetchFromYahoo(symbol);
        if (!fetched.isEmpty()) {
            cache.put(symbol, fetched);
            cacheTime.put(symbol, LocalDateTime.now(IST));
            return tail(fetched, count);
        }
        // Return stale cache rather than empty on transient failure
        List<CandleDTO> stale = cache.get(symbol);
        return stale != null ? tail(stale, count) : Collections.emptyList();
    }

    @Override
    public Optional<Double> getLastPrice(String symbol) {
        List<CandleDTO> candles = getCandles(symbol, 1);
        if (candles.isEmpty()) return Optional.empty();
        return Optional.of(candles.get(candles.size() - 1).getClose());
    }

    @Override
    public boolean isAvailable() {
        try {
            return !fetchFromYahoo("RELIANCE").isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void evictCache(String symbol) {
        cache.remove(symbol);
        cacheTime.remove(symbol);
        log.info("[DataFeed] Cache evicted for {}", symbol);
    }

    // ── Fetch from Yahoo Finance ──────────────────────────────

    private List<CandleDTO> fetchFromYahoo(String symbol) {
        try {
            String url  = String.format(YAHOO_URL, symbol);
            String json = restTemplate.getForObject(url, String.class);
            return parseYahoo(symbol, json);
        } catch (Exception e) {
            log.warn("[DataFeed] Yahoo fetch failed for {}: {}", symbol, e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<CandleDTO> parseYahoo(String symbol, String json) {
        List<CandleDTO> result = new ArrayList<>();
        try {
            JsonNode root    = objectMapper.readTree(json);
            JsonNode result0 = root.path("chart").path("result").get(0);
            if (result0 == null) return result;

            JsonNode ts   = result0.path("timestamp");
            JsonNode q    = result0.path("indicators").path("quote").get(0);
            if (q == null) return result;

            for (int i = 0; i < ts.size(); i++) {
                double c = q.path("close").get(i).isNull() ? 0 : q.path("close").get(i).asDouble();
                if (c <= 0) continue;
                result.add(CandleDTO.builder()
                        .symbol(symbol)
                        .timestamp(LocalDateTime.ofInstant(
                                Instant.ofEpochSecond(ts.get(i).asLong()), IST))
                        .open(r2(q.path("open").get(i).asDouble()))
                        .high(r2(q.path("high").get(i).asDouble()))
                        .low(r2(q.path("low").get(i).asDouble()))
                        .close(r2(c))
                        .volume(q.path("volume").get(i).asLong())
                        .build());
            }
            log.debug("[DataFeed] Fetched {} candles for {}", result.size(), symbol);
        } catch (Exception e) {
            log.error("[DataFeed] Parse error {}: {}", symbol, e.getMessage());
        }
        return result;
    }

    // ── Helpers ───────────────────────────────────────────────

    private boolean isFresh(String symbol) {
        LocalDateTime t = cacheTime.get(symbol);
        return t != null && LocalDateTime.now(IST).isBefore(t.plusSeconds(ttlSeconds));
    }

    private List<CandleDTO> tail(List<CandleDTO> list, int n) {
        if (list.size() <= n) return list;
        return new ArrayList<>(list.subList(list.size() - n, list.size()));
    }

    private double r2(double v) { return Math.round(v * 100.0) / 100.0; }
}
