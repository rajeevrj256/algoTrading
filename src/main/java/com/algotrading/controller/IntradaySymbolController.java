package com.algotrading.controller;

import com.algotrading.dto.ApiResponse;
import com.algotrading.dto.IntradaySymbolDTO;
import com.algotrading.service.IntradaySymbolService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/symbols")
@RequiredArgsConstructor
public class IntradaySymbolController {

    private final IntradaySymbolService intradaySymbolService;

    @GetMapping("/resolved")
    public ResponseEntity<ApiResponse<List<String>>> resolvedSymbols() {
        return ResponseEntity.ok(ApiResponse.ok("Resolved symbols loaded", intradaySymbolService.getSymbolsForScan()));
    }

    @GetMapping("/active")
    public ResponseEntity<ApiResponse<List<IntradaySymbolDTO>>> activeSymbols() {
        return ResponseEntity.ok(ApiResponse.ok("Active intraday symbols loaded",
                intradaySymbolService.getActiveSymbolSnapshot()));
    }

    @PostMapping("/replace")
    public ResponseEntity<ApiResponse<String>> replaceSymbols(
            @RequestParam(defaultValue = "external-updater") String source,
            @RequestBody List<IntradaySymbolDTO> symbols) {
        intradaySymbolService.replaceSymbols(symbols, source);
        return ResponseEntity.ok(ApiResponse.ok("Intraday symbol shortlist replaced"));
    }

    @PostMapping("/cache/refresh")
    public ResponseEntity<ApiResponse<String>> refreshCache() {
        intradaySymbolService.refreshCache();
        return ResponseEntity.ok(ApiResponse.ok("Intraday symbol cache refreshed"));
    }
}
