package com.algotrading.util;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Locks the Groww groww_symbol formats used by /v1/historical/candles.
 * Wrong casing/strike here silently yields empty candles (all signals "skipped").
 */
class SymbolsTest {

    @Test
    void cashSymbol_equity_and_index() {
        assertEquals("NSE-RELIANCE", Symbols.growwCashSymbol("RELIANCE"));
        assertEquals("NSE-RELIANCE", Symbols.growwCashSymbol("reliance"));
        assertEquals("NSE-NIFTY", Symbols.growwCashSymbol("NIFTY_50"));   // alias → canonical
        assertEquals("NSE-BANKNIFTY", Symbols.growwCashSymbol("NIFTY_BANK"));
    }

    @Test
    void optionSymbol_matches_groww_dash_format() {
        // Groww example shape: NSE-NIFTY-30Sep25-24650-CE (dd + Mmm + yy, integer strike)
        assertEquals("NSE-NIFTY-08Jul25-24500-CE",
                Symbols.growwOptionSymbol("NIFTY", LocalDate.of(2025, 7, 8), 24500, "CE"));
        assertEquals("NSE-BANKNIFTY-30Sep25-52000-PE",
                Symbols.growwOptionSymbol("BANKNIFTY", LocalDate.of(2025, 9, 30), 52000.0, "PE"));
    }
}
