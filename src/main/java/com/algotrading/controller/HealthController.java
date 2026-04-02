package com.algotrading.controller;

import com.algotrading.dto.ApiResponse;
import com.algotrading.dto.HealthReportDTO;
import com.algotrading.service.HealthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * HealthController — application health check endpoints.
 *
 * GET /api/health/check   — run a fresh full check now
 * GET /api/health/last    — return last cached result
 * GET /api/health/count   — number of checks since startup
 */
@Slf4j
@RestController
@RequestMapping("/api/health")
@RequiredArgsConstructor
public class HealthController {

    private final HealthService healthService;

    @GetMapping("/check")
    public ResponseEntity<ApiResponse<HealthReportDTO>> runCheck() {
        return ResponseEntity.ok(ApiResponse.ok(healthService.runCheck()));
    }

    @GetMapping("/last")
    public ResponseEntity<ApiResponse<HealthReportDTO>> lastReport() {
        return ResponseEntity.ok(ApiResponse.ok(healthService.getLastReport()));
    }

    @GetMapping("/count")
    public ResponseEntity<ApiResponse<Integer>> checkCount() {
        return ResponseEntity.ok(ApiResponse.ok(healthService.getCheckCount()));
    }
}
