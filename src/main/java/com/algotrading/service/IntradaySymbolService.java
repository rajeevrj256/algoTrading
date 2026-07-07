package com.algotrading.service;

import com.algotrading.dto.IntradaySymbolDTO;

import java.util.List;

public interface IntradaySymbolService {
    /** Equity symbols for the cash-equity engine pass (index symbols excluded). */
    List<String> getSymbolsForScan();

    /** Configured index underlyings for the F&O engine pass (NIFTY, BANKNIFTY, ...). */
    List<String> getFnoUnderlyings();

    List<IntradaySymbolDTO> getActiveSymbolSnapshot();
    void replaceSymbols(List<IntradaySymbolDTO> symbols, String source);
    void refreshCache();
}
