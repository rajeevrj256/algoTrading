package com.algotrading.controller;

import com.algotrading.dto.AlertDTO;
import com.algotrading.dto.ApiResponse;
import com.algotrading.dto.PositionDTO;
import com.algotrading.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * NotificationController — REST API for sending alerts.
 *
 * POST /api/notify/alert
 * POST /api/notify/trade-open
 * POST /api/notify/trade-close
 * POST /api/notify/circuit-breaker?loss=
 * GET  /api/notify/status
 */
@Slf4j
@RestController
@RequestMapping("/api/notify")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @PostMapping("/alert")
    public ResponseEntity<ApiResponse<String>> sendAlert(@RequestBody AlertDTO alert) {
        notificationService.sendAlert(alert);
        return ResponseEntity.ok(ApiResponse.ok("Alert dispatched"));
    }

    @PostMapping("/trade-open")
    public ResponseEntity<ApiResponse<String>> notifyOpen(@RequestBody PositionDTO position) {
        notificationService.notifyTradeOpen(position);
        return ResponseEntity.ok(ApiResponse.ok("Trade open notification sent"));
    }

    @PostMapping("/trade-close")
    public ResponseEntity<ApiResponse<String>> notifyClose(@RequestBody PositionDTO position) {
        notificationService.notifyTradeClose(position);
        return ResponseEntity.ok(ApiResponse.ok("Trade close notification sent"));
    }

    @PostMapping("/circuit-breaker")
    public ResponseEntity<ApiResponse<String>> circuitBreaker(@RequestParam double loss) {
        notificationService.notifyCircuitBreaker(loss);
        return ResponseEntity.ok(ApiResponse.ok("Circuit breaker alert sent"));
    }

    @GetMapping("/status")
    public ResponseEntity<ApiResponse<String>> status() {
        boolean enabled = notificationService.isTelegramEnabled();
        String msg = enabled ? "Telegram active"
                             : "Telegram NOT configured — set bot-token and chat-id";
        return ResponseEntity.ok(enabled ? ApiResponse.ok(msg) : ApiResponse.error(msg));
    }
}
