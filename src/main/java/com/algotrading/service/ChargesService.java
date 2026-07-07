package com.algotrading.service;

import com.algotrading.enums.InstrumentType;
import com.algotrading.enums.SignalType;
import com.algotrading.model.TradeCharges;

/**
 * ChargesService — calculates round-trip trading charges and net P&L.
 */
public interface ChargesService {

    /** Cash-equity charges (intraday/delivery per config). */
    TradeCharges calculate(SignalType signal, double entryPrice, double exitPrice, int quantity);

    /**
     * Instrument-aware charges. INDEX_OPTION uses the option cost model
     * (flat brokerage, STT on sell premium, option exchange/stamp rates);
     * anything else falls back to the equity model.
     */
    TradeCharges calculate(SignalType signal, double entryPrice, double exitPrice, int quantity,
                           InstrumentType instrumentType);
}
