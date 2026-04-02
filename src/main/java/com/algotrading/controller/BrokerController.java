package com.algotrading.controller;

import com.algotrading.dto.ApiResponse;
import com.algotrading.dto.PositionDTO;
import com.algotrading.dto.TradeSignalDTO;
import com.algotrading.model.Order;
import com.algotrading.service.BrokerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

/**
 * BrokerController — REST API for paper trading.
 *
 * POST /api/broker/open
 * POST /api/broker/close/{positionId}?exitPrice=&reason=
 * POST /api/broker/check-exits/{symbol}?price=
 * POST /api/broker/square-off?price=
 * GET  /api/broker/positions/open
 * GET  /api/broker/positions/closed
 * GET  /api/broker/positions/{positionId}
 * GET  /api/broker/orders
 */
@Slf4j
@RestController
@RequestMapping("/api/broker")
@RequiredArgsConstructor
public class BrokerController {

    private final BrokerService brokerService;

    @PostMapping("/open")
    public ResponseEntity<ApiResponse<PositionDTO>> openPosition(@RequestBody TradeSignalDTO signal) {
        PositionDTO pos = brokerService.openPosition(signal);
        return ResponseEntity.ok(ApiResponse.ok("Position opened", pos));
    }

    @PostMapping("/close/{positionId}")
    public ResponseEntity<ApiResponse<PositionDTO>> closePosition(
            @PathVariable String positionId,
            @RequestParam double exitPrice,
            @RequestParam(defaultValue = "MANUAL CLOSE") String reason) {

        PositionDTO closed = brokerService.closePosition(positionId, exitPrice, reason);
        return closed != null
                ? ResponseEntity.ok(ApiResponse.ok("Position closed", closed))
                : ResponseEntity.ok(ApiResponse.error("Position not found: " + positionId));
    }

    @PostMapping("/check-exits/{symbol}")
    public ResponseEntity<ApiResponse<List<PositionDTO>>> checkExits(
            @PathVariable String symbol,
            @RequestParam double price) {

        List<PositionDTO> closed = brokerService.checkExits(symbol.toUpperCase(), price);
        return ResponseEntity.ok(ApiResponse.ok("Exit check complete — " + closed.size() + " closed", closed));
    }

    @PostMapping("/square-off")
    public ResponseEntity<ApiResponse<List<PositionDTO>>> squareOff(@RequestParam double price) {
        List<PositionDTO> closed = brokerService.squareOffAll(price);
        return ResponseEntity.ok(ApiResponse.ok("Square-off complete", closed));
    }

    @GetMapping("/positions/open")
    public ResponseEntity<ApiResponse<List<PositionDTO>>> openPositions() {
        return ResponseEntity.ok(ApiResponse.ok(brokerService.getOpenPositions()));
    }

    @GetMapping("/positions/closed")
    public ResponseEntity<ApiResponse<List<PositionDTO>>> closedPositions() {
        return ResponseEntity.ok(ApiResponse.ok(brokerService.getClosedPositions()));
    }

    @GetMapping("/positions/{positionId}")
    public ResponseEntity<ApiResponse<PositionDTO>> getPosition(@PathVariable String positionId) {
        Optional<PositionDTO> pos = brokerService.findPosition(positionId);
        return pos
                .map(p -> ResponseEntity.ok(ApiResponse.ok(p)))
                .orElseGet(() -> ResponseEntity.ok(ApiResponse.error("Not found: " + positionId)));
    }

    @GetMapping("/orders")
    public ResponseEntity<ApiResponse<List<Order>>> orders() {
        return ResponseEntity.ok(ApiResponse.ok(brokerService.getOrders()));
    }
}
