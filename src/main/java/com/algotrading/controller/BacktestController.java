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
}
