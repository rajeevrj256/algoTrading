package com.algotrading.model;

import com.algotrading.enums.OptionType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * OptionContract — a resolved NSE index option leg.
 *
 * underlying     canonical index name (NIFTY, BANKNIFTY, ...)
 * scanSymbol     the symbol the scan used (e.g. NIFTY_50) — kept for spot LTP lookups
 * tradingSymbol  NSE trading symbol (e.g. NIFTY25JUL24500CE / NIFTY2570824500CE)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OptionContract {
    private String     underlying;
    private String     scanSymbol;
    private String     tradingSymbol;
    private OptionType optionType;
    private double     strike;
    private LocalDate  expiry;
    private int        lotSize;
    private boolean    monthly;      // true = monthly contract symbol format
}
