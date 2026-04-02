package com.algotrading.controller;

import com.algotrading.dto.ApiResponse;
import com.algotrading.dto.CandleDTO;
import com.algotrading.service.DataFeedService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

/**
 * DataFeedController — REST API for candle data.
 *
 * GET  /api/feed/candles/{symbol}?count=120
 * GET  /api/feed/price/{symbol}
 * DELETE /api/feed/cache/{symbol}
 * GET  /api/feed/status
 */
@Slf4j
@RestController
@RequestMapping("/api/feed")
@RequiredArgsConstructor
public class DataFeedController {

    private final DataFeedService dataFeedService;

    @GetMapping("/candles/{symbol}")
    public ResponseEntity<ApiResponse<List<CandleDTO>>> getCandles(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "120") int count) {

        List<CandleDTO> candles = dataFeedService.getCandles(symbol.toUpperCase(), count);
        return candles.isEmpty()
                ? ResponseEntity.ok(ApiResponse.error("No data available for " + symbol))
                : ResponseEntity.ok(ApiResponse.ok(candles));
    }

    @GetMapping("/price/{symbol}")
    public ResponseEntity<ApiResponse<Double>> getLastPrice(@PathVariable String symbol) {
        Optional<Double> price = dataFeedService.getLastPrice(symbol.toUpperCase());
        return price
                .map(p -> ResponseEntity.ok(ApiResponse.ok(p)))
                .orElseGet(() -> ResponseEntity.ok(ApiResponse.error("Price unavailable for " + symbol)));
    }

    @DeleteMapping("/cache/{symbol}")
    public ResponseEntity<ApiResponse<String>> evictCache(@PathVariable String symbol) {
        dataFeedService.evictCache(symbol.toUpperCase());
        return ResponseEntity.ok(ApiResponse.ok("Cache cleared for " + symbol));
    }

    @GetMapping("/status")
    public ResponseEntity<ApiResponse<String>> feedStatus() {
        boolean up = dataFeedService.isAvailable();
        String msg = "Yahoo Finance feed is " + (up ? "UP" : "DOWN");
        return ResponseEntity.ok(up ? ApiResponse.ok(msg) : ApiResponse.error(msg));
    }
}
