package com.algotrading.util;

import lombok.experimental.UtilityClass;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Symbols — canonical index-name resolution shared by the data feed and the
 * option chain service ("NIFTY_50" / "NIFTY50" / "NSE_NIFTY" → "NIFTY").
 */
@UtilityClass
public class Symbols {

    private static final Map<String, String> INDEX_ALIASES = buildAliases();

    /** Groww groww_symbol expiry token, e.g. 08Jul25 (case-sensitive on Groww). */
    private static final DateTimeFormatter GROWW_EXPIRY = DateTimeFormatter.ofPattern("ddMMMyy", Locale.ENGLISH);

    /** Canonical index name, or null when the symbol is not a known index. */
    public static String canonicalIndex(String symbol) {
        if (symbol == null) return null;
        return INDEX_ALIASES.get(normalize(symbol));
    }

    /**
     * Groww groww_symbol for a CASH instrument (equity or index):
     * "RELIANCE" → "NSE-RELIANCE", "NIFTY_50" → "NSE-NIFTY".
     * (Groww's current /v1/historical/candles keys instruments by groww_symbol.)
     */
    public static String growwCashSymbol(String symbol) {
        String canonical = canonicalIndex(symbol);
        String base = canonical != null ? canonical : normalize(symbol).replace('_', '-');
        return "NSE-" + base;
    }

    /**
     * Groww groww_symbol for an index option, e.g.
     * ("NIFTY", 2025-07-08, 24500, "CE") → "NSE-NIFTY-08Jul25-24500-CE".
     */
    public static String growwOptionSymbol(String underlying, LocalDate expiry, double strike, String optionType) {
        return "NSE-" + underlying + "-" + GROWW_EXPIRY.format(expiry) + "-" + (long) strike + "-" + optionType;
    }

    public static boolean isIndex(String symbol) {
        return canonicalIndex(symbol) != null;
    }

    public static String normalize(String symbol) {
        return symbol == null ? "" : symbol.trim().toUpperCase().replace('-', '_');
    }

    private static Map<String, String> buildAliases() {
        Map<String, String> aliases = new HashMap<>();
        add(aliases, "NIFTY", Arrays.asList("NIFTY", "NIFTY50", "NIFTY_50", "NSE_NIFTY", "NSE_NIFTY_50"));
        add(aliases, "BANKNIFTY", Arrays.asList("BANKNIFTY", "NIFTY_BANK", "BANK_NIFTY", "NSE_BANKNIFTY"));
        add(aliases, "FINNIFTY", Arrays.asList("FINNIFTY", "NIFTY_FIN_SERVICE", "NIFTY_FINANCIAL_SERVICES"));
        add(aliases, "MIDCPNIFTY", Arrays.asList("MIDCPNIFTY", "NIFTY_MIDCAP_SELECT", "MIDCAP_SELECT"));
        add(aliases, "SENSEX", Arrays.asList("SENSEX", "BSE_SENSEX"));
        return Collections.unmodifiableMap(aliases);
    }

    private static void add(Map<String, String> aliases, String canonical, Collection<String> values) {
        for (String value : values) {
            aliases.put(normalize(value), canonical);
        }
    }
}
