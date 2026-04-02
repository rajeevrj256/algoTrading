package com.algotrading.controller;

import com.algotrading.dto.*;
import com.algotrading.service.NotificationService;
import com.algotrading.service.SheetsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * SheetsController — REST API for Google Sheets operations.
 *
 * POST /api/sheets/trade
 * POST /api/sheets/open-positions
 * POST /api/sheets/daily-summary
 * GET  /api/sheets/status
 */
@Slf4j
@RestController
@RequestMapping("/api/sheets")
@RequiredArgsConstructor
public class SheetsController {

    private final SheetsService sheetsService;

    @PostMapping("/trade")
    public ResponseEntity<ApiResponse<String>> logTrade(@RequestBody PositionDTO position) {
        sheetsService.logTrade(position);
        return ResponseEntity.ok(ApiResponse.ok("Trade logged to Sheets"));
    }

    @PostMapping("/open-positions")
    public ResponseEntity<ApiResponse<String>> updateOpenPositions(@RequestBody List<PositionDTO> positions) {
        sheetsService.updateOpenPositions(positions);
        return ResponseEntity.ok(ApiResponse.ok("Open positions tab refreshed"));
    }

    @PostMapping("/daily-summary")
    public ResponseEntity<ApiResponse<String>> updateDailySummary(@RequestBody DailySummaryDTO summary) {
        sheetsService.updateDailySummary(summary);
        return ResponseEntity.ok(ApiResponse.ok("Daily summary updated"));
    }

    @GetMapping("/status")
    public ResponseEntity<ApiResponse<String>> status() {
        boolean conn = sheetsService.isConnected();
        String msg = conn ? "Connected — " + sheetsService.getSheetUrl()
                          : "NOT connected — configure sheets.sheet-id in application.yml";
        return ResponseEntity.ok(conn ? ApiResponse.ok(msg) : ApiResponse.error(msg));
    }
}
