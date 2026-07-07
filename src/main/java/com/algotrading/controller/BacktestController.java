package com.algotrading.controller;

import com.algotrading.dto.ApiResponse;
import com.algotrading.dto.BacktestResultDTO;
import com.algotrading.enums.StrategyType;
import com.algotrading.service.BacktestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * BacktestController — replay strategies over historical candles.
 *
 * POST /api/backtest/run?symbols=RELIANCE,TCS&strategy=VWAP_TREND&count=500
 *   - symbols  : CSV of symbols (required)
 *   - strategy : one StrategyType, or omit for ALL loaded strategies
 *   - count    : candles to pull per symbol (default 500)
 */
@Slf4j
@RestController
@RequestMapping("/api/backtest")
@RequiredArgsConstructor
public class BacktestController {

    private final BacktestService backtestService;

    @PostMapping("/run")
    public ResponseEntity<ApiResponse<List<BacktestResultDTO>>> run(
            @RequestParam String symbols,
            @RequestParam(required = false) StrategyType strategy,
            @RequestParam(defaultValue = "500") int count) {

        List<String> symbolList = Arrays.stream(symbols.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());

        if (symbolList.isEmpty()) {
            return ResponseEntity.ok(ApiResponse.error("No symbols provided"));
        }

        List<BacktestResultDTO> results = backtestService.run(symbolList, strategy, count);
        return ResponseEntity.ok(ApiResponse.ok(
                String.format("Backtest complete — %d symbol(s), %d strategy result(s)",
                        symbolList.size(), results.size()),
                results));
    }

    /**
     * Intraday EQUITY backtest over Groww CASH history.
     * POST /api/backtest/equity?symbols=RELIANCE,TCS&strategy=VWAP_TREND&days=30
     */
    @PostMapping("/equity")
    public ResponseEntity<ApiResponse<List<BacktestResultDTO>>> equity(
            @RequestParam String symbols,
            @RequestParam(required = false) StrategyType strategy,
            @RequestParam(defaultValue = "30") int days) {

        List<String> symbolList = parseSymbols(symbols);
        if (symbolList.isEmpty()) {
            return ResponseEntity.ok(ApiResponse.error("No symbols provided"));
        }
        log.info("[Backtest][EQUITY] ▶ request received — symbols={} strategy={} days={}",
                symbolList, strategy == null ? "ALL" : strategy, days);
        long t0 = System.currentTimeMillis();
        List<BacktestResultDTO> results = backtestService.runEquity(symbolList, strategy, days);
        log.info("[Backtest][EQUITY] ■ done in {} ms — {} strategy result(s)",
                System.currentTimeMillis() - t0, results.size());
        return ResponseEntity.ok(ApiResponse.ok(
                String.format("Equity backtest complete — %d symbol(s), %dd, %d strategy result(s)",
                        symbolList.size(), days, results.size()),
                results));
    }

    /**
     * F&O (index options) backtest on REAL Groww FNO premium candles.
     * POST /api/backtest/fno?symbols=NIFTY,BANKNIFTY&strategy=INDEX_TREND&days=30
     */
    @PostMapping("/fno")
    public ResponseEntity<ApiResponse<List<BacktestResultDTO>>> fno(
            @RequestParam String symbols,
            @RequestParam(required = false) StrategyType strategy,
            @RequestParam(defaultValue = "30") int days) {

        List<String> symbolList = parseSymbols(symbols);
        if (symbolList.isEmpty()) {
            return ResponseEntity.ok(ApiResponse.error("No symbols provided"));
        }
        List<BacktestResultDTO> results = backtestService.runFno(symbolList, strategy, days);
        return ResponseEntity.ok(ApiResponse.ok(
                String.format("F&O backtest complete — %d index(es), %dd, %d strategy result(s)",
                        symbolList.size(), days, results.size()),
                results));
    }

    private List<String> parseSymbols(String symbols) {
        return Arrays.stream(symbols.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
    }
}
