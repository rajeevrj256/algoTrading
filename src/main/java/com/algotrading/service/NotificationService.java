package com.algotrading.service;

import com.algotrading.dto.AlertDTO;
import com.algotrading.dto.PositionDTO;

/**
 * NotificationService — dispatches trade and system alerts.
 *
 * Implementation: TelegramNotificationServiceImpl
 * Falls back to console-only logging if Telegram is not configured.
 */
public interface NotificationService {

    /** Send a generic structured alert. */
    void sendAlert(AlertDTO alert);

    /** Send a trade-opened notification. */
    void notifyTradeOpen(PositionDTO position);

    /** Send a trade-closed notification with P&L. */
    void notifyTradeClose(PositionDTO position);

    /** Send an urgent circuit-breaker alert. */
    void notifyCircuitBreaker(double totalLoss);

    /** Return true if Telegram credentials are configured. */
    boolean isTelegramEnabled();
}
