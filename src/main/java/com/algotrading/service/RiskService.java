package com.algotrading.service;

import com.algotrading.dto.DailySummaryDTO;
import com.algotrading.dto.RiskValidationDTO;
import com.algotrading.dto.TradeSignalDTO;

/**
 * RiskService — validates signals and manages daily risk state.
 *
 * Implementation: RiskServiceImpl
 */
public interface RiskService {

    /**
     * Check if the system is allowed to open a new trade right now.
     * Considers: circuit breaker, daily trade limit, daily target, market hours.
     */
    RiskValidationDTO canTrade();

    /**
     * Validate a specific signal against risk rules.
     * Checks: confidence threshold, R:R ratio, quantity, max risk per trade.
     */
    RiskValidationDTO validateSignal(TradeSignalDTO signal);

    /**
     * Record a closed trade's P&L.
     * Triggers the circuit breaker if the daily loss limit is breached.
     */
    void recordTrade(double pnl);

    /** Return today's aggregated daily summary. */
    DailySummaryDTO getDailySummary();

    /** Reset daily stats — called at the start of each trading day. */
    void resetDay();

    /** Return whether the circuit breaker is currently tripped. */
    boolean isCircuitTripped();
}
