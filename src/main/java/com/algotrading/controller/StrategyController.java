package com.algotrading.controller;

import com.algotrading.dto.ApiResponse;
import com.algotrading.dto.CandleDTO;
import com.algotrading.dto.TradeSignalDTO;
import com.algotrading.enums.StrategyType;
import com.algotrading.service.StrategyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * StrategyController — REST API for strategy execution.
 *
 * POST /api/strategy/scan/{symbol}                — run all strategies
 * POST /api/strategy/scan/{symbol}/{strategyType} — run one strategy
 * GET  /api/strategy/list                         — list loaded strategies
 * GET  /api/strategy/config                       — enabled/disabled state of each
 * POST /api/strategy/config/{type}/enable         — enable at runtime (persists)
 * POST /api/strategy/config/{type}/disable        — disable at runtime (persists)
 * POST /api/strategy/config/refresh               — reload enabled set from DB
 */
@Slf4j
@RestController
@RequestMapping("/api/strategy")
@RequiredArgsConstructor
public class StrategyController {

    private final StrategyService strategyService;

    @PostMapping("/scan/{symbol}")
    public ResponseEntity<ApiResponse<TradeSignalDTO>> scanAll(
            @PathVariable String symbol,
            @RequestBody List<CandleDTO> candles) {

        Optional<TradeSignalDTO> signal = strategyService.runStrategies(symbol.toUpperCase(), candles);
        return signal
                .map(s -> ResponseEntity.ok(ApiResponse.ok("Signal generated", s)))
                .orElseGet(() -> ResponseEntity.ok(ApiResponse.ok("No signal", null)));
    }

    @PostMapping("/scan/{symbol}/{strategyType}")
    public ResponseEntity<ApiResponse<TradeSignalDTO>> scanOne(
            @PathVariable String symbol,
            @PathVariable StrategyType strategyType,
            @RequestBody List<CandleDTO> candles) {

        Optional<TradeSignalDTO> signal =
                strategyService.runStrategy(strategyType, symbol.toUpperCase(), candles);
        return signal
                .map(s -> ResponseEntity.ok(ApiResponse.ok("Signal generated", s)))
                .orElseGet(() -> ResponseEntity.ok(ApiResponse.ok("No signal for " + strategyType, null)));
    }

    @GetMapping("/list")
    public ResponseEntity<ApiResponse<List<String>>> listStrategies() {
        return ResponseEntity.ok(ApiResponse.ok(strategyService.listStrategies()));
    }

    // ── Runtime enable/disable (persisted to strategy_config) ──

    @GetMapping("/config")
    public ResponseEntity<ApiResponse<Map<String, Boolean>>> config() {
        return ResponseEntity.ok(ApiResponse.ok(strategyService.getStrategyStatus()));
    }

    @PostMapping("/config/{type}/enable")
    public ResponseEntity<ApiResponse<Map<String, Boolean>>> enable(@PathVariable StrategyType type) {
        strategyService.setStrategyEnabled(type, true);
        return ResponseEntity.ok(ApiResponse.ok(type + " enabled", strategyService.getStrategyStatus()));
    }

    @PostMapping("/config/{type}/disable")
    public ResponseEntity<ApiResponse<Map<String, Boolean>>> disable(@PathVariable StrategyType type) {
        strategyService.setStrategyEnabled(type, false);
        return ResponseEntity.ok(ApiResponse.ok(type + " disabled", strategyService.getStrategyStatus()));
    }

    @PostMapping("/config/refresh")
    public ResponseEntity<ApiResponse<Map<String, Boolean>>> refresh() {
        strategyService.refreshEnabledStrategies();
        return ResponseEntity.ok(ApiResponse.ok("Reloaded from strategy_config", strategyService.getStrategyStatus()));
    }
}
