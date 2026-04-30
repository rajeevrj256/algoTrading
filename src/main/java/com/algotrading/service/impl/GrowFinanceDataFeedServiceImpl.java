package com.algotrading.service.impl;

import com.algotrading.dto.CandleDTO;
import com.algotrading.service.DataFeedService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Groww Trading API implementation of DataFeedService.
 *
 * Endpoints used:
 *   - GET /v1/historical/candle/range  → 5-minute OHLCV candles
 *   - GET /v1/live-data/ltp            → last traded price
 *
 * Auth: Bearer access token in Authorization header.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.datafeed.provider", havingValue = "groww")
public class GrowFinanceDataFeedServiceImpl implements DataFeedService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String EXCHANGE = "NSE";
    private static final String SEGMENT = "CASH";
    private static final int CANDLE_INTERVAL_MIN = 5;
    private static final int LOOKBACK_DAYS = 5;
    private static final DateTimeFormatter GROWW_CANDLE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final Map<String, String> SYMBOL_ALIASES = buildAliases();

    @Value("${groww.base-url:https://api.groww.in}")
    private String baseUrl;

    @Value("${groww.access-token:}")
    private String configuredAccessToken;

    @Value("${groww.api-key:}")
    private String apiKey;

    @Value("${groww.api-secret:}")
    private String apiSecret;

    @Value("${groww.api-version:1.0}")
    private String apiVersion;

    @Value("${groww.min-request-gap-ms:150}")
    private long minRequestGapMs;

    private volatile String currentAccessToken;
    private volatile Instant tokenExpiry;
    private volatile long lastRequestAtMs;

    @Value("${datafeed.cache.ttl-seconds:60}")
    private int ttlSeconds;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    private final Map<String, List<CandleDTO>> cache = new ConcurrentHashMap<>();
    private final Map<String, LocalDateTime> cacheTime = new ConcurrentHashMap<>();

    // ── DataFeedService impl ──────────────────────────────────

    @Override
    public List<CandleDTO> getCandles(String symbol, int count) {
        if (isFresh(symbol)) {
            List<CandleDTO> cached = cache.get(symbol);
            if (cached != null) return tail(cached, count);
        }
        List<CandleDTO> fetched = fetchCandles(symbol);
        if (!fetched.isEmpty()) {
            cache.put(symbol, fetched);
            cacheTime.put(symbol, LocalDateTime.now(IST));
            return tail(fetched, count);
        }
        List<CandleDTO> stale = cache.get(symbol);
        return stale != null ? tail(stale, count) : Collections.emptyList();
    }

    @Override
    public Optional<Double> getLastPrice(String symbol) {
        try {
            String exchangeSymbol = growwExchangeSymbol(symbol);
            String url = UriComponentsBuilder.fromHttpUrl(baseUrl + "/v1/live-data/ltp")
                    .queryParam("segment", SEGMENT)
                    .queryParam("exchange_symbols", exchangeSymbol)
                    .toUriString();

            ResponseEntity<String> resp = authorizedGet(url);
            JsonNode root = objectMapper.readTree(resp.getBody());
            JsonNode price = root.path("payload").path(exchangeSymbol);
            if (price.isMissingNode() || price.isNull()) return Optional.empty();
            return Optional.of(price.asDouble());
        } catch (Exception e) {
            log.warn("[Groww] LTP fetch failed for {}: {}", symbol, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public boolean isAvailable() {
        try {
            return getAccessToken() != null && getLastPrice("RELIANCE").isPresent();
        } catch (Exception e) {
            log.warn("[Groww] isAvailable failed: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public void evictCache(String symbol) {
        cache.remove(symbol);
        cacheTime.remove(symbol);
        log.info("[Groww] Cache evicted for {}", symbol);
    }

    // ── Fetch from Groww ──────────────────────────────────────

    private List<CandleDTO> fetchCandles(String symbol) {
        try {
            long endEpochMillis = Instant.now().toEpochMilli();
            long startEpochMillis = endEpochMillis - (LOOKBACK_DAYS * 24L * 60 * 60 * 1000);

            String url = UriComponentsBuilder.fromHttpUrl(baseUrl + "/v1/historical/candle/range")
                    .queryParam("exchange", EXCHANGE)
                    .queryParam("segment", SEGMENT)
                    .queryParam("trading_symbol", growwTradingSymbol(symbol))
                    .queryParam("start_time", startEpochMillis)
                    .queryParam("end_time", endEpochMillis)
                    .queryParam("interval_in_minutes", CANDLE_INTERVAL_MIN)
                    .toUriString();

            ResponseEntity<String> resp = authorizedGet(url);
            return parseCandles(symbol, resp.getBody());
        } catch (Exception e) {
            log.warn("[Groww] Candle fetch failed for {}: {}", symbol, e.getMessage());
            return Collections.emptyList();
        }
    }

    // ── Auth: API key + secret → daily access token ─────────────

    private ResponseEntity<String> authorizedGet(String url) {
        try {
            throttleRequestRate();
            return restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(authHeaders()), String.class);
        } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden e) {
            log.info("[Groww] Token rejected ({}), refreshing and retrying", e.getStatusCode());
            currentAccessToken = null;
            tokenExpiry = null;
            throttleRequestRate();
            return restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(authHeaders()), String.class);
        }
    }

    private synchronized String getAccessToken() {
        if (configuredAccessToken != null && !configuredAccessToken.isEmpty()) {
            return configuredAccessToken;
        }
        if (currentAccessToken != null && tokenExpiry != null && Instant.now().isBefore(tokenExpiry)) {
            return currentAccessToken;
        }
        if (apiKey == null || apiKey.isEmpty() || apiSecret == null || apiSecret.isEmpty()) {
            log.warn("[Groww] No api-key/api-secret configured and no access-token set");
            return null;
        }
        return exchangeForAccessToken();
    }

    private String exchangeForAccessToken() {
        try {
            String timestamp = String.valueOf(Instant.now().getEpochSecond());
            String checksum = sha256Hex(apiSecret + timestamp);

            Map<String, String> body = new HashMap<>();
            body.put("key_type", "approval");
            body.put("checksum", checksum);
            body.put("timestamp", timestamp);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
            headers.setBearerAuth(apiKey);

            ResponseEntity<String> resp = restTemplate.exchange(
                    baseUrl + "/v1/token/api/access",
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    String.class);

            JsonNode root = objectMapper.readTree(resp.getBody());
            String token = root.path("token").asText(null);
            if (token == null || token.isEmpty()) {
                log.error("[Groww] Token exchange returned no token: {}", resp.getBody());
                return null;
            }
            currentAccessToken = token;
            // Groww access tokens expire daily at 6 AM IST — cache until then.
            tokenExpiry = nextExpiry();
            log.info("[Groww] Access token refreshed, valid until {}", tokenExpiry);
            return token;
        } catch (Exception e) {
            log.error("[Groww] Token exchange failed: {}", e.getMessage());
            return null;
        }
    }

    private Instant nextExpiry() {
        LocalDateTime now = LocalDateTime.now(IST);
        LocalDateTime sixAm = now.toLocalDate().atTime(6, 0);
        if (!now.isBefore(sixAm)) sixAm = sixAm.plusDays(1);
        return sixAm.atZone(IST).toInstant();
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private List<CandleDTO> parseCandles(String symbol, String json) {
        List<CandleDTO> result = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode candles = root.path("payload").path("candles");
            if (!candles.isArray()) return result;

            for (JsonNode c : candles) {
                if (!c.isArray() || c.size() < 6) continue;
                LocalDateTime timestamp = parseTimestamp(c.get(0));
                if (timestamp == null) continue;
                double open = c.get(1).asDouble();
                double high = c.get(2).asDouble();
                double low = c.get(3).asDouble();
                double close = c.get(4).asDouble();
                long volume = c.get(5).asLong();
                if (close <= 0) continue;

                result.add(CandleDTO.builder()
                        .symbol(symbol)
                        .timestamp(timestamp)
                        .open(r2(open))
                        .high(r2(high))
                        .low(r2(low))
                        .close(r2(close))
                        .volume(volume)
                        .build());
            }
            log.debug("[Groww] Fetched {} candles for {}", result.size(), symbol);
        } catch (Exception e) {
            log.error("[Groww] Parse error {}: {}", symbol, e.getMessage());
        }
        return result;
    }

    // ── Helpers ───────────────────────────────────────────────

    private HttpHeaders authHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        String token = getAccessToken();
        if (token != null) h.setBearerAuth(token);
        h.set("X-API-VERSION", apiVersion);
        return h;
    }

    private boolean isFresh(String symbol) {
        LocalDateTime t = cacheTime.get(symbol);
        return t != null && LocalDateTime.now(IST).isBefore(t.plusSeconds(ttlSeconds));
    }

    private List<CandleDTO> tail(List<CandleDTO> list, int n) {
        if (list.size() <= n) return list;
        return new ArrayList<>(list.subList(list.size() - n, list.size()));
    }

    private String growwExchangeSymbol(String symbol) {
        return EXCHANGE + "_" + growwTradingSymbol(symbol);
    }

    private String growwHistoricalSymbol(String symbol) {
        return EXCHANGE + "-" + growwTradingSymbol(symbol);
    }

    private String growwTradingSymbol(String symbol) {
        String normalized = normalizeSymbol(symbol);
        return SYMBOL_ALIASES.containsKey(normalized) ? SYMBOL_ALIASES.get(normalized) : normalized;
    }

    private String normalizeSymbol(String symbol) {
        return symbol == null ? "" : symbol.trim().toUpperCase().replace('-', '_');
    }

    private LocalDateTime parseTimestamp(JsonNode value) {
        if (value == null || value.isNull()) return null;
        if (value.isNumber()) {
            return LocalDateTime.ofInstant(Instant.ofEpochSecond(value.asLong()), IST);
        }

        String raw = value.asText("");
        if (raw.isEmpty()) return null;

        try {
            return LocalDateTime.parse(raw, GROWW_CANDLE_TIME);
        } catch (Exception ignored) {
            log.debug("[Groww] Unparseable candle timestamp: {}", raw);
            return null;
        }
    }

    private synchronized void throttleRequestRate() {
        if (minRequestGapMs <= 0) return;

        long now = System.currentTimeMillis();
        long waitMs = (lastRequestAtMs + minRequestGapMs) - now;
        if (waitMs > 0) {
            try {
                Thread.sleep(waitMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        lastRequestAtMs = System.currentTimeMillis();
    }

    private static Map<String, String> buildAliases() {
        Map<String, String> aliases = new HashMap<>();
        addAliases(aliases, "NIFTY", Arrays.asList("NIFTY", "NIFTY50", "NIFTY_50", "NSE_NIFTY", "NSE_NIFTY_50"));
        addAliases(aliases, "BANKNIFTY", Arrays.asList("BANKNIFTY", "NIFTY_BANK", "BANK_NIFTY", "NSE_BANKNIFTY"));
        addAliases(aliases, "FINNIFTY", Arrays.asList("FINNIFTY", "NIFTY_FIN_SERVICE", "NIFTY_FINANCIAL_SERVICES"));
        addAliases(aliases, "MIDCPNIFTY", Arrays.asList("MIDCPNIFTY", "NIFTY_MIDCAP_SELECT", "MIDCAP_SELECT"));
        addAliases(aliases, "SENSEX", Arrays.asList("SENSEX", "BSE_SENSEX"));
        return Collections.unmodifiableMap(aliases);
    }

    private static void addAliases(Map<String, String> aliases, String canonical, Collection<String> values) {
        for (String value : values) {
            aliases.put(value.trim().toUpperCase().replace('-', '_'), canonical);
        }
    }

    private double r2(double v) { return Math.round(v * 100.0) / 100.0; }
}
