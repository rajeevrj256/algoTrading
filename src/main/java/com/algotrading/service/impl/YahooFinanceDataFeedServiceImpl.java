package com.algotrading.service.impl;

import com.algotrading.dto.CandleDTO;
import com.algotrading.event.TradingEventPublisher;
import com.algotrading.service.DataFeedService;
import com.algotrading.util.Symbols;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
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
 *
 * Gotchas handled here:
 *  - Yahoo returns 429 for the default Java user-agent — every request sends a
 *    browser UA.
 *  - Indices don't take the ".NS" suffix; they map to Yahoo index tickers
 *    (NIFTY_50 → ^NSEI, BANKNIFTY → ^NSEBANK, ...).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.datafeed.provider", havingValue = "yahoo", matchIfMissing = true)
public class YahooFinanceDataFeedServiceImpl implements DataFeedService {

    private static final String YAHOO_URL =
            "https://query1.finance.yahoo.com/v8/finance/chart/%s?interval=5m&range=5d";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** Canonical index name → Yahoo ticker (verified live 2026-07). */
    private static final Map<String, String> INDEX_TICKERS = buildIndexTickers();

    private static Map<String, String> buildIndexTickers() {
        Map<String, String> m = new HashMap<>();
        m.put("NIFTY", "^NSEI");
        m.put("BANKNIFTY", "^NSEBANK");
        m.put("FINNIFTY", "NIFTY_FIN_SERVICE.NS");
        m.put("MIDCPNIFTY", "NIFTY_MID_SELECT.NS");
        m.put("SENSEX", "^BSESN");
        return Collections.unmodifiableMap(m);
    }

    @Value("${datafeed.cache.ttl-seconds:60}")
    private int ttlSeconds;

    /** Yahoo's unofficial API is burst-sensitive — space requests out. */
    @Value("${datafeed.yahoo.min-request-gap-ms:1200}")
    private long minRequestGapMs;

    /** After a 429, stop calling Yahoo for this long and serve the stale cache. */
    @Value("${datafeed.yahoo.rate-limit-cooldown-seconds:180}")
    private long rateLimitCooldownSeconds;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final TradingEventPublisher eventPublisher;

    private final Map<String, List<CandleDTO>> cache     = new ConcurrentHashMap<>();
    private final Map<String, LocalDateTime>   cacheTime = new ConcurrentHashMap<>();

    private volatile long lastRequestAtMs;
    private volatile Instant rateLimitedUntil;
    private volatile int consecutive429s;

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
            // Persist live candles (equity + index underlyings like NIFTY/BANKNIFTY)
            // async via Kafka (direct save when Kafka off) → candle_history. Parity with the Groww feed.
            eventPublisher.publishCandles(symbol, fetched);
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
            // During a 429 cooldown the feed is degraded, not dead — stale cache
            // still serves scans/exits. Report available if we have ANY data.
            if (inRateLimitCooldown()) {
                return !cache.isEmpty();
            }
            return !fetchFromYahoo("RELIANCE").isEmpty() || !cache.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String getProviderName() {
        return "Yahoo Finance";
    }

    @Override
    public void evictCache(String symbol) {
        cache.remove(symbol);
        cacheTime.remove(symbol);
        log.info("[DataFeed] Cache evicted for {}", symbol);
    }

    // ── Fetch from Yahoo Finance ──────────────────────────────

    private List<CandleDTO> fetchFromYahoo(String symbol) {
        if (inRateLimitCooldown()) {
            log.debug("[DataFeed] {} skipped — Yahoo 429 cooldown until {}", symbol, rateLimitedUntil);
            return Collections.emptyList();
        }
        try {
            throttleRequestRate();
            String url  = String.format(YAHOO_URL, yahooTicker(symbol));
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.USER_AGENT, USER_AGENT);   // Yahoo 429s the default Java UA
            String json = restTemplate
                    .exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class)
                    .getBody();
            consecutive429s = 0;
            return parseYahoo(symbol, json);
        } catch (org.springframework.web.client.HttpClientErrorException.TooManyRequests e) {
            // Escalating back-off: 3 min, 6 min, 12 min... cap 30 min. Serving the
            // stale cache during cooldown beats hammering an IP-banned endpoint.
            consecutive429s++;
            long cooldown = Math.min(rateLimitCooldownSeconds * (1L << Math.min(consecutive429s - 1, 3)), 1800);
            rateLimitedUntil = Instant.now().plusSeconds(cooldown);
            log.warn("[DataFeed] Yahoo rate-limited (429 #{}) — backing off {}s, serving cached data",
                    consecutive429s, cooldown);
            return Collections.emptyList();
        } catch (Exception e) {
            log.warn("[DataFeed] Yahoo fetch failed for {}: {}", symbol, e.getMessage());
            return Collections.emptyList();
        }
    }

    private boolean inRateLimitCooldown() {
        Instant until = rateLimitedUntil;
        return until != null && Instant.now().isBefore(until);
    }

    /** Space requests out — Yahoo tolerates a steady trickle, not 14-symbol bursts. */
    private synchronized void throttleRequestRate() {
        if (minRequestGapMs <= 0) return;
        long waitMs = (lastRequestAtMs + minRequestGapMs) - System.currentTimeMillis();
        if (waitMs > 0) {
            try {
                Thread.sleep(waitMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        lastRequestAtMs = System.currentTimeMillis();
    }

    /** Indices use Yahoo index tickers; equities get the ".NS" suffix. */
    private String yahooTicker(String symbol) {
        String canonical = Symbols.canonicalIndex(symbol);
        if (canonical != null && INDEX_TICKERS.containsKey(canonical)) {
            return INDEX_TICKERS.get(canonical);
        }
        return Symbols.normalize(symbol) + ".NS";
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
