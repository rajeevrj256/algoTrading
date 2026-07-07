package com.algotrading.service;

import com.algotrading.dto.BacktestResultDTO;
import com.algotrading.enums.StrategyType;

import java.util.List;

/**
 * BacktestService — replays stored historical candles through the strategies and
 * reports per-strategy expectancy, so a strategy can be validated WITHOUT waiting
 * a live trading window.
 *
 * Implementation: BacktestServiceImpl
 */
public interface BacktestService {

    /**
     * Backtest the given symbols.
     * @param symbols  symbols to fetch candles for and replay
     * @param type     a single strategy to test, or null for every loaded strategy
     * @param count    how many recent candles to pull per symbol
     */
    List<BacktestResultDTO> run(List<String> symbols, StrategyType type, int count);

    /**
     * Intraday EQUITY (cash) backtest — pulls {@code lookbackDays} of Groww CASH
     * historical candles per symbol (persisting them), replays each strategy in the
     * underlying frame, and charges the cash-equity cost model.
     */
    List<BacktestResultDTO> runEquity(List<String> symbols, StrategyType type, int lookbackDays);

    /**
     * F&O (index options) backtest — replays index strategies on the underlying's
     * Groww CASH candles, converts each signal to the ATM option contract it would
     * have traded as-of that bar, and simulates the trade on REAL Groww FNO premium
     * candles (exit decided on the underlying frame, premium hard-stop guard, FNO
     * charges). Signals with no available option data are counted as skipped.
     */
    List<BacktestResultDTO> runFno(List<String> symbols, StrategyType type, int lookbackDays);
}
