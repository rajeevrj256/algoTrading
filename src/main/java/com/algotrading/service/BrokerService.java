package com.algotrading.service;

import com.algotrading.dto.PositionDTO;
import com.algotrading.dto.TradeSignalDTO;
import com.algotrading.model.Order;

import java.util.List;
import java.util.Optional;

/**
 * BrokerService — manages paper order execution and position lifecycle.
 *
 * Implementation: PaperBrokerServiceImpl
 */
public interface BrokerService {

    /**
     * Open a new paper position from a validated signal.
     * Applies slippage simulation and returns the opened position.
     */
    PositionDTO openPosition(TradeSignalDTO signal);

    /**
     * Close an open position at the given exit price and reason.
     * Returns null if positionId is not found.
     */
    PositionDTO closePosition(String positionId, double exitPrice, String exitReason);

    /**
     * Check open positions for a symbol against the current price.
     * Automatically closes any position that hit its stop-loss or target.
     * Returns a list of newly closed positions (may be empty).
     */
    List<PositionDTO> checkExits(String symbol, double currentPrice);

    /**
     * End-of-day: close every remaining open position at the given price.
     */
    List<PositionDTO> squareOffAll(double currentPrice);

    /** Return all currently open positions. */
    List<PositionDTO> getOpenPositions();

    /** Return all closed positions (today). */
    List<PositionDTO> getClosedPositions();

    /** Find a position by ID — searches both open and closed. */
    Optional<PositionDTO> findPosition(String positionId);

    /** Return all orders placed today. */
    List<Order> getOrders();
}
