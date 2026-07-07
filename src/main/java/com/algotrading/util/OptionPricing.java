package com.algotrading.util;

import com.algotrading.enums.OptionType;
import lombok.experimental.UtilityClass;

/**
 * OptionPricing — pure-static Black-Scholes pricing/delta for European index options.
 *
 * Used for (a) synthetic premium fallback when the live option LTP is unavailable and
 * (b) delta-mapping underlying SL/target distances into premium terms.
 * Paper-trading approximation — no dividends, flat IV.
 */
@UtilityClass
public class OptionPricing {

    /** Theoretical premium. spot/strike in ₹, t in years, iv+rate annualised. */
    public static double blackScholes(OptionType type, double spot, double strike,
                                      double tYears, double iv, double rate) {
        if (spot <= 0 || strike <= 0) return 0;
        double t = Math.max(tYears, 1.0 / (365 * 8));    // floor ≈ 1 trading hour
        double sig = Math.max(iv, 0.01);
        double d1 = (Math.log(spot / strike) + (rate + sig * sig / 2) * t) / (sig * Math.sqrt(t));
        double d2 = d1 - sig * Math.sqrt(t);
        if (type == OptionType.CE) {
            return spot * cnd(d1) - strike * Math.exp(-rate * t) * cnd(d2);
        }
        return strike * Math.exp(-rate * t) * cnd(-d2) - spot * cnd(-d1);
    }

    /** Signed delta: CE in (0,1), PE in (−1,0). */
    public static double delta(OptionType type, double spot, double strike,
                               double tYears, double iv, double rate) {
        if (spot <= 0 || strike <= 0) return type == OptionType.CE ? 0.5 : -0.5;
        double t = Math.max(tYears, 1.0 / (365 * 8));
        double sig = Math.max(iv, 0.01);
        double d1 = (Math.log(spot / strike) + (rate + sig * sig / 2) * t) / (sig * Math.sqrt(t));
        return type == OptionType.CE ? cnd(d1) : cnd(d1) - 1.0;
    }

    /** Abramowitz-Stegun cumulative normal approximation (|err| < 7.5e-8). */
    private static double cnd(double x) {
        double l = Math.abs(x);
        double k = 1.0 / (1.0 + 0.2316419 * l);
        double w = 1.0 - 1.0 / Math.sqrt(2 * Math.PI) * Math.exp(-l * l / 2)
                * (0.31938153 * k - 0.356563782 * k * k + 1.781477937 * Math.pow(k, 3)
                - 1.821255978 * Math.pow(k, 4) + 1.330274429 * Math.pow(k, 5));
        return x < 0 ? 1.0 - w : w;
    }
}
