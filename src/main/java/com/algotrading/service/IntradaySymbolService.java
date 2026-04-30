package com.algotrading.service;

import com.algotrading.dto.IntradaySymbolDTO;

import java.util.List;

public interface IntradaySymbolService {
    List<String> getSymbolsForScan();
    List<IntradaySymbolDTO> getActiveSymbolSnapshot();
    void replaceSymbols(List<IntradaySymbolDTO> symbols, String source);
    void refreshCache();
}
