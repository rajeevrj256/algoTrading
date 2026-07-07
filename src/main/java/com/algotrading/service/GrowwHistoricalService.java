package com.algotrading.service;

import com.algotrading.dto.CandleDTO;

import java.time.LocalDate;
import java.util.List;

/**
 * GrowwHistoricalService — pulls historical OHLCV candles from the Groww paid
 * historical-data API over an arbitrary window (wider than the live 5-day feed),
 * for BOTH segments:
 *   - CASH : index / equity underlying candles
 *   - FNO  : option premium candles for a resolved option trading symbol
 *
 * Every successful pull is also persisted to candle_history (upsert on symbol+ts)
 * so backtests build real depth and re-runs hit the DB instead of the API.
 *
 * Implementation: GrowwHistoricalServiceImpl
 */
public interface GrowwHistoricalService {

    /** Cash-segment candles for an index/equity symbol over the last {@code lookbackDays}. */
    List<CandleDTO> equityCandles(String symbol, int lookbackDays);

    /**
     * FNO-segment premium candles for a resolved option trading symbol
     * (e.g. NIFTY2570824500CE) over an explicit [from, to] date window (inclusive).
     * Empty when Groww has no data for that (usually expired) contract.
     */
    List<CandleDTO> optionCandles(String optionTradingSymbol, LocalDate from, LocalDate to);
}
