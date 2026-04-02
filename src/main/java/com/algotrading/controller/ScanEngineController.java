package com.algotrading.controller;

import com.algotrading.dto.ApiResponse;
import com.algotrading.service.ScanOrchestrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * ScanEngineController — manual trigger endpoints.
 *
 * POST /api/engine/scan    — trigger an immediate scan (no need to wait for scheduler)
 * POST /api/engine/eod     — trigger manual EOD square-off
 * GET  /api/engine/status  — engine status
 */
@Slf4j
@RestController
@RequestMapping("/api/engine")
@RequiredArgsConstructor
public class ScanEngineController {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ScanOrchestrationService orchestrationService;

    @PostMapping("/scan")
    public ResponseEntity<ApiResponse<String>> triggerScan() {
        log.info("[Engine] Manual scan triggered via API");
        try {
            orchestrationService.runScan();
            return ResponseEntity.ok(ApiResponse.ok("Scan completed at " + now()));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error("Scan failed: " + e.getMessage()));
        }
    }

    @PostMapping("/eod")
    public ResponseEntity<ApiResponse<String>> triggerEod() {
        log.info("[Engine] Manual EOD triggered via API");
        try {
            orchestrationService.runEod();
            return ResponseEntity.ok(ApiResponse.ok("EOD completed at " + now()));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error("EOD failed: " + e.getMessage()));
        }
    }

    @GetMapping("/status")
    public ResponseEntity<ApiResponse<String>> status() {
        return ResponseEntity.ok(ApiResponse.ok("Scan Engine running — " + now() + " IST"));
    }

    private String now() {
        return LocalDateTime.now(IST).format(FMT);
    }
}
