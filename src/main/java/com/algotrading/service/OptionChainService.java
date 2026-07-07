package com.algotrading.service;

import com.algotrading.dto.PositionDTO;
import com.algotrading.dto.TradeSignalDTO;
import com.algotrading.enums.SignalType;
import com.algotrading.model.OptionContract;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * OptionChainService — turns index signals into option-chain paper orders.
 *
 * BUY index signal  → buy CE at/near ATM of the nearest expiry.
 * SELL index signal → buy PE (long options only — no naked writing, no margin model).
 *
 * Implementation: GrowwOptionChainServiceImpl (Groww FNO LTP with a
 * Black-Scholes synthetic fallback).
 */
public interface OptionChainService {

    /** Master switch (fno.enabled). */
    boolean isFnoEnabled();

    /** True when the scan symbol is an index configured under fno.underlyings. */
    boolean isOptionUnderlying(String scanSymbol);

    /**
     * Convert an index-frame signal into an option-frame signal:
     * resolves expiry + ATM strike, fetches the premium, maps SL/target into
     * premium terms via delta, and sizes the position in lots.
     * Empty when no contract can be resolved/afforded.
     */
    Optional<TradeSignalDTO> toOptionSignal(TradeSignalDTO indexSignal);

    /** Live premium for an open option position (Groww FNO LTP → synthetic fallback). */
    Optional<Double> getOptionLtp(PositionDTO position);

    /**
     * Resolve the option contract a signal WOULD trade as-of a given instant
     * (expiry nearest that date, ATM ± offset strike, NSE trading symbol, lot size).
     * Pure structural resolution — no live pricing, no expiry-cutoff guard — so the
     * backtest builds the exact same contract the live flow would. Empty when the
     * symbol is not a configured underlying or no expiry resolves.
     */
    Optional<OptionContract> resolveContractAsOf(String scanSymbol, SignalType direction,
                                                 double spot, LocalDateTime asOf);

    /**
     * Lots sized so premium risk (entry − premium stop) ≈ fno.risk-per-trade-pct of
     * capital, clamped by max lots and max premium outlay. 0 = cannot trade within caps.
     * Shared by the live flow and the F&O backtest so sizing is identical.
     */
    int sizeOptionLots(double premium, double premiumStop, int lotSize);
}
