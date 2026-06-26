package com.algotrading.controller;

import com.algotrading.dto.ApiResponse;
import com.algotrading.dto.DailySummaryDTO;
import com.algotrading.dto.RiskValidationDTO;
import com.algotrading.dto.StrategyExpectancyDTO;
import com.algotrading.dto.TradeSignalDTO;
import com.algotrading.service.RiskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * RiskController — REST API for risk management.
 *
 * GET  /api/risk/can-trade
 * POST /api/risk/validate
 * POST /api/risk/record?pnl=
 * GET  /api/risk/summary
 * GET  /api/risk/expectancy?days=7
 * POST /api/risk/reset
 * GET  /api/risk/circuit-breaker
 */
@Slf4j
@RestController
@RequestMapping("/api/risk")
@RequiredArgsConstructor
public class RiskController {

    private final RiskService riskService;

    @GetMapping("/can-trade")
    public ResponseEntity<ApiResponse<RiskValidationDTO>> canTrade() {
        return ResponseEntity.ok(ApiResponse.ok(riskService.canTrade()));
    }

    @PostMapping("/validate")
    public ResponseEntity<ApiResponse<RiskValidationDTO>> validate(@RequestBody TradeSignalDTO signal) {
        RiskValidationDTO systemCheck = riskService.canTrade();
        if (!systemCheck.isApproved())
            return ResponseEntity.ok(ApiResponse.ok(systemCheck));
        return ResponseEntity.ok(ApiResponse.ok(riskService.validateSignal(signal)));
    }

    @PostMapping("/record")
    public ResponseEntity<ApiResponse<DailySummaryDTO>> recordTrade(@RequestParam double pnl) {
        riskService.recordTrade(pnl);
        return ResponseEntity.ok(ApiResponse.ok("Trade recorded", riskService.getDailySummary()));
    }

    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<DailySummaryDTO>> summary() {
        return ResponseEntity.ok(ApiResponse.ok(riskService.getDailySummary()));
    }

    /**
     * Per-strategy realized expectancy over the last N days (default 7).
     * Sorted best avg-P&L first. Disable any strategy with negative avgPnl/avgR.
     */
    @GetMapping("/expectancy")
    public ResponseEntity<ApiResponse<List<StrategyExpectancyDTO>>> expectancy(
            @RequestParam(defaultValue = "7") int days) {
        return ResponseEntity.ok(ApiResponse.ok(riskService.getStrategyExpectancy(days)));
    }

    @PostMapping("/reset")
    public ResponseEntity<ApiResponse<String>> reset() {
        riskService.resetDay();
        return ResponseEntity.ok(ApiResponse.ok("Daily stats reset"));
    }

    @GetMapping("/circuit-breaker")
    public ResponseEntity<ApiResponse<Boolean>> circuitBreaker() {
        boolean tripped = riskService.isCircuitTripped();
        return ResponseEntity.ok(ApiResponse.ok(
                tripped ? "CIRCUIT BREAKER TRIPPED" : "Circuit breaker clear", tripped));
    }
}
