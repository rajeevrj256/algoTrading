package com.algotrading.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * FnoProperties — all F&O (index options) tunables, bound from the fno: yaml block.
 *
 * Lot sizes / strike steps / expiry days change with SEBI+NSE circulars — they are
 * config, never code. Update application.yml when the exchange revises them.
 */
@Data
@Component
@ConfigurationProperties(prefix = "fno")
public class FnoProperties {

    /** Master switch: convert index signals into option paper orders. */
    private boolean enabled = false;

    /** Target % of capital risked per option trade (premium risk = entry − premium stop). */
    private double riskPerTradePct = 0.75;

    /**
     * Options are lumpy — 1 lot is the minimum position and its risk can exceed
     * the target. Allow a single lot up to this HARD cap; beyond it, skip the trade.
     */
    private double maxRiskPerTradePct = 2.5;

    /** Never buy more than this many lots on one signal. */
    private int maxLotsPerTrade = 4;

    /** Cap on total premium outlay (entry × lotSize × lots) per trade, ₹. */
    private double maxPremiumPerTrade = 30000;

    /** Catastrophic guard: exit if premium falls this % below entry, regardless of the underlying. */
    private double premiumHardStopPct = 35;

    /** 0 = ATM. Positive = steps IN the money (higher delta, less theta risk). */
    private int itmOffsetSteps = 0;

    /** IV used for the synthetic Black-Scholes fallback premium + delta mapping. */
    private double syntheticIv = 0.14;

    /** Annualised risk-free rate for Black-Scholes. */
    private double riskFreeRate = 0.065;

    /** Skip new option entries this many minutes before expiry-day close (theta crush). */
    private int expiryDayEntryCutoffMin = 90;

    private Map<String, Underlying> underlyings = new LinkedHashMap<>();

    @Data
    public static class Underlying {
        private int lotSize;
        private int strikeStep;
        private DayOfWeek expiryDay = DayOfWeek.TUESDAY;
        /** true = weekly contracts exist (NIFTY); false = monthly only. */
        private boolean weeklyExpiry = false;
    }
}
