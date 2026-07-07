package com.algotrading.service.impl;

import com.algotrading.dto.CandleDTO;
import com.algotrading.event.TradingEventPublisher;
import com.algotrading.service.FnoCandleHistoryService;
import com.algotrading.service.GrowwAuthService;
import com.algotrading.service.GrowwHistoricalService;
import com.algotrading.util.Symbols;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * GrowwHistoricalServiceImpl — Groww paid historical-data client for backtesting.
 *
 * Uses GET /v1/historical/candle/range (the same endpoint the live Groww feed
 * uses) but over an arbitrary, chunked window and for either segment (CASH for
 * underlyings, FNO for option premiums). Chunks stay under Groww's per-request
 * range limit and are stitched in chronological order. Every pull is persisted
 * (equity → candle_history, F&O → fno_candle_history) via the event publisher —
 * Kafka async when enabled, direct save otherwise — so live never blocks on the DB
 * write and re-runs can read the DB instead of the API.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GrowwHistoricalServiceImpl implements GrowwHistoricalService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String EXCHANGE = "NSE";

    private final GrowwAuthService authService;
    private final ObjectMapper objectMapper;
    private final TradingEventPublisher eventPublisher;
    private final FnoCandleHistoryService fnoCandleHistoryService;

    @Value("${groww.base-url:https://api.groww.in}")
    private String baseUrl;

    @Value("${backtest.groww.interval-minutes:5}")
    private int intervalMinutes;

    @Value("${backtest.groww.max-days-per-request:25}")
    private int maxDaysPerRequest;

    @Override
    public List<CandleDTO> equityCandles(String symbol, int lookbackDays) {
        String label = cashLabel(symbol);              // storage / CandleDTO key: NIFTY, RELIANCE
        String growwSymbol = Symbols.growwCashSymbol(symbol);   // Groww groww_symbol: NSE-NIFTY, NSE-RELIANCE
        LocalDate to = LocalDate.now(IST);
        LocalDate from = to.minusDays(Math.max(1, lookbackDays));
        log.info("[GrowwHist] equityCandles {} → groww_symbol={} window {}..{} (creds={})",
                label, growwSymbol, from, to, authService.hasCredentials());
        List<CandleDTO> candles = fetchRange("CASH", label, growwSymbol, from, to);
        // Persist async via Kafka (direct save when Kafka off) → candle_history.
        eventPublisher.publishCandles(label, candles);
        return candles;
    }

    @Override
    public List<CandleDTO> optionCandles(String growwOptionSymbol, LocalDate from, LocalDate to) {
        if (growwOptionSymbol == null || growwOptionSymbol.trim().isEmpty()) return new ArrayList<CandleDTO>();
        // Groww groww_symbol is case-sensitive (e.g. NSE-NIFTY-08Jul25-24500-CE) — do NOT upper-case.
        String sym = growwOptionSymbol.trim();

        // Read-through: reuse stored F&O candles for this range instead of re-hitting Groww.
        List<CandleDTO> cached = fnoCandleHistoryService.loadRange(sym,
                from.atStartOfDay(), to.atTime(LocalTime.of(23, 59, 59)));
        if (!cached.isEmpty()) {
            log.debug("[GrowwHist] {} — reused {} stored F&O candles ({}..{})", sym, cached.size(), from, to);
            return cached;
        }

        List<CandleDTO> candles = fetchRange("FNO", sym, sym, from, to);
        // Persist async via Kafka (direct save when Kafka off) → fno_candle_history.
        eventPublisher.publishFnoCandles(sym, candles);
        return candles;
    }

    // ── Groww fetch ───────────────────────────────────────────

    /**
     * Chunked historical pull over [from, to], stitched in time order.
     * {@code label} keys the returned CandleDTOs (and persistence); {@code growwSymbol}
     * is Groww's groww_symbol (NSE-RELIANCE / NSE-NIFTY-08Jul25-24500-CE) used on the wire.
     */
    private List<CandleDTO> fetchRange(String segment, String label, String growwSymbol, LocalDate from, LocalDate to) {
        List<CandleDTO> all = new ArrayList<CandleDTO>();
        if (!authService.hasCredentials()) {
            log.warn("[GrowwHist] no Groww credentials — cannot fetch {} {}", segment, growwSymbol);
            return all;
        }
        int chunk = Math.max(1, maxDaysPerRequest);
        LocalDate cursor = from;
        while (!cursor.isAfter(to)) {
            LocalDate chunkEnd = cursor.plusDays(chunk - 1);
            if (chunkEnd.isAfter(to)) chunkEnd = to;
            all.addAll(fetchChunk(segment, label, growwSymbol, cursor, chunkEnd));
            cursor = chunkEnd.plusDays(1);
        }
        return all;
    }

    private List<CandleDTO> fetchChunk(String segment, String label, String growwSymbol, LocalDate from, LocalDate to) {
        try {
            // Groww /v1/historical/candles accepts epoch SECONDS (or "yyyy-MM-dd HH:mm:ss").
            // Seconds are digits-only → no space to double-encode through RestTemplate.
            long startSec = from.atStartOfDay(IST).toInstant().getEpochSecond();
            long endSec = to.atTime(LocalTime.of(23, 59, 59)).atZone(IST).toInstant().getEpochSecond();

            String url = UriComponentsBuilder.fromHttpUrl(baseUrl + "/v1/historical/candles")
                    .queryParam("exchange", EXCHANGE)
                    .queryParam("segment", segment)
                    .queryParam("groww_symbol", growwSymbol)
                    .queryParam("start_time", startSec)
                    .queryParam("end_time", endSec)
                    .queryParam("candle_interval", candleInterval())
                    .toUriString();

            log.info("[GrowwHist] → GET /v1/historical/candles {} {} {}..{} ({})",
                    segment, growwSymbol, from, to, candleInterval());
            ResponseEntity<String> resp = authService.get(url);
            List<CandleDTO> parsed = parseCandles(label, resp.getBody());
            log.info("[GrowwHist] ← {} {} — {} candle(s) [{}]", segment, growwSymbol, parsed.size(),
                    resp.getStatusCodeValue());
            return parsed;
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            log.debug("[GrowwHist] {} {} — no data (404) for {}..{}", segment, growwSymbol, from, to);
            return new ArrayList<CandleDTO>();
        } catch (org.springframework.web.client.HttpClientErrorException.Forbidden e) {
            log.warn("[GrowwHist] {} {} — FORBIDDEN (403). Historical data scope missing on this plan.",
                    segment, growwSymbol);
            return new ArrayList<CandleDTO>();
        } catch (Exception e) {
            log.warn("[GrowwHist] {} {} fetch failed ({}..{}): {}", segment, growwSymbol, from, to, e.getMessage());
            return new ArrayList<CandleDTO>();
        }
    }

    /** Groww candle_interval token for the configured minute granularity. */
    private String candleInterval() {
        switch (intervalMinutes) {
            case 1:    return "1minute";
            case 5:    return "5minute";
            case 10:   return "10minute";
            case 15:   return "15minute";
            case 30:   return "30minute";
            case 60:   return "1hour";
            case 240:  return "4hours";
            case 1440: return "1day";
            default:   return intervalMinutes + "minute";
        }
    }

    private List<CandleDTO> parseCandles(String symbol, String json) {
        List<CandleDTO> result = new ArrayList<CandleDTO>();
        try {
            JsonNode candles = objectMapper.readTree(json).path("payload").path("candles");
            if (!candles.isArray()) return result;
            for (JsonNode c : candles) {
                if (!c.isArray() || c.size() < 6) continue;
                LocalDateTime ts = parseTimestamp(c.get(0));
                if (ts == null) continue;
                double close = c.get(4).asDouble();
                if (close <= 0) continue;
                result.add(CandleDTO.builder()
                        .symbol(symbol)
                        .timestamp(ts)
                        .open(c.get(1).asDouble())
                        .high(c.get(2).asDouble())
                        .low(c.get(3).asDouble())
                        .close(close)
                        .volume(c.get(5).asLong())
                        .build());
            }
        } catch (Exception e) {
            log.warn("[GrowwHist] parse failed for {}: {}", symbol, e.getMessage());
        }
        return result;
    }

    private LocalDateTime parseTimestamp(JsonNode value) {
        if (value == null || value.isNull()) return null;
        if (value.isNumber()) {
            return LocalDateTime.ofInstant(Instant.ofEpochSecond(value.asLong()), IST);
        }
        String raw = value.asText("");
        if (raw.isEmpty()) return null;
        try {
            return LocalDateTime.parse(raw.replace(" ", "T"));
        } catch (Exception ignored) {
            log.debug("[GrowwHist] unparseable candle timestamp: {}", raw);
            return null;
        }
    }

    /** Storage/label key: canonical index name (NIFTY_50 → NIFTY) or the plain equity symbol. */
    private String cashLabel(String symbol) {
        String canonical = Symbols.canonicalIndex(symbol);
        return canonical != null ? canonical : symbol.trim().toUpperCase();
    }
}
