package com.algotrading.service;

import com.algotrading.enums.SignalType;
import com.algotrading.model.TradeCharges;

/**
 * ChargesService — calculates round-trip trading charges and net P&L.
 */
public interface ChargesService {
    TradeCharges calculate(SignalType signal, double entryPrice, double exitPrice, int quantity);
}
