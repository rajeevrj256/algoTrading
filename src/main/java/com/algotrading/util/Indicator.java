package com.algotrading.util;

import com.algotrading.dto.CandleDTO;
import lombok.experimental.UtilityClass;

import java.util.List;

/**
 * Indicator — pure-static, stateless technical indicator calculations.
 * No Spring beans. No side effects. Thread-safe by design.
 */
@UtilityClass
public class Indicator {

    // ── EMA ──────────────────────────────────────────────────

    public static double[] ema(double[] prices, int period) {
        double[] result = new double[prices.length];
        double k = 2.0 / (period + 1);
        result[0] = prices[0];
        for (int i = 1; i < prices.length; i++) {
            result[i] = (prices[i] - result[i - 1]) * k + result[i - 1];
        }
        return result;
    }

    // ── RSI ──────────────────────────────────────────────────

    public static double[] rsi(double[] prices, int period) {
        double[] result = new double[prices.length];
        double avgGain = 0, avgLoss = 0;
        for (int i = 1; i <= period && i < prices.length; i++) {
            double d = prices[i] - prices[i - 1];
            if (d > 0) avgGain += d; else avgLoss += Math.abs(d);
        }
        avgGain /= period;
        avgLoss /= period;
        result[Math.min(period, prices.length - 1)] =
                avgLoss == 0 ? 100 : 100 - 100 / (1 + avgGain / avgLoss);
        for (int i = period + 1; i < prices.length; i++) {
            double d    = prices[i] - prices[i - 1];
            double gain = d > 0 ? d : 0;
            double loss = d < 0 ? Math.abs(d) : 0;
            avgGain = (avgGain * (period - 1) + gain) / period;
            avgLoss = (avgLoss * (period - 1) + loss) / period;
            result[i] = avgLoss == 0 ? 100 : 100 - 100 / (1 + avgGain / avgLoss);
        }
        return result;
    }

    // ── ATR ──────────────────────────────────────────────────

    public static double[] atr(List<CandleDTO> candles, int period) {
        int n = candles.size();
        double[] tr  = new double[n];
        double[] res = new double[n];
        tr[0] = candles.get(0).getHigh() - candles.get(0).getLow();
        for (int i = 1; i < n; i++) {
            double h = candles.get(i).getHigh(), l = candles.get(i).getLow(),
                   pc = candles.get(i - 1).getClose();
            tr[i] = Math.max(h - l, Math.max(Math.abs(h - pc), Math.abs(l - pc)));
        }
        double sum = 0;
        for (int i = 0; i < period && i < n; i++) sum += tr[i];
        res[Math.min(period - 1, n - 1)] = sum / period;
        for (int i = period; i < n; i++)
            res[i] = (res[i - 1] * (period - 1) + tr[i]) / period;
        return res;
    }

    // ── VWAP ─────────────────────────────────────────────────

    public static double[] vwap(List<CandleDTO> candles) {
        int n = candles.size();
        double[] res = new double[n];
        double cumTpVol = 0, cumVol = 0;
        for (int i = 0; i < n; i++) {
            CandleDTO c = candles.get(i);
            double tp = (c.getHigh() + c.getLow() + c.getClose()) / 3.0;
            cumTpVol += tp * c.getVolume();
            cumVol   += c.getVolume();
            res[i]    = cumVol > 0 ? cumTpVol / cumVol : tp;
        }
        return res;
    }

    // ── Rolling StdDev ────────────────────────────────────────

    public static double[] rollingStd(double[] prices, int period) {
        double[] res = new double[prices.length];
        for (int i = period - 1; i < prices.length; i++) {
            double mean = 0;
            for (int j = i - period + 1; j <= i; j++) mean += prices[j];
            mean /= period;
            double var = 0;
            for (int j = i - period + 1; j <= i; j++) {
                double d = prices[j] - mean; var += d * d;
            }
            res[i] = Math.sqrt(var / period);
        }
        return res;
    }

    // ── MACD ─────────────────────────────────────────────────

    public static double[][] macd(double[] prices, int fast, int slow, int signal) {
        double[] fastEma  = ema(prices, fast);
        double[] slowEma  = ema(prices, slow);
        double[] macdLine = new double[prices.length];
        for (int i = 0; i < prices.length; i++) macdLine[i] = fastEma[i] - slowEma[i];
        double[] sigLine  = ema(macdLine, signal);
        double[] hist     = new double[prices.length];
        for (int i = 0; i < prices.length; i++) hist[i] = macdLine[i] - sigLine[i];
        return new double[][]{macdLine, sigLine, hist};
    }

    // ── Supertrend ────────────────────────────────────────────

    public static SupertrendResult supertrend(List<CandleDTO> candles, int period, double mult) {
        int n = candles.size();
        double[] atrArr = atr(candles, period);
        double[] ub = new double[n], lb = new double[n];
        double[] st = new double[n];
        int[]    dir = new int[n];

        for (int i = 0; i < n; i++) {
            double hl2 = (candles.get(i).getHigh() + candles.get(i).getLow()) / 2.0;
            ub[i] = hl2 + mult * atrArr[i];
            lb[i] = hl2 - mult * atrArr[i];
        }
        for (int i = 1; i < n; i++) {
            ub[i] = (ub[i] < ub[i-1] || candles.get(i-1).getClose() > ub[i-1]) ? ub[i] : ub[i-1];
            lb[i] = (lb[i] > lb[i-1] || candles.get(i-1).getClose() < lb[i-1]) ? lb[i] : lb[i-1];
        }
        dir[0] = 1; st[0] = lb[0];
        for (int i = 1; i < n; i++) {
            if (st[i-1] == ub[i-1])
                dir[i] = candles.get(i).getClose() > ub[i] ? 1 : -1;
            else
                dir[i] = candles.get(i).getClose() < lb[i] ? -1 : 1;
            st[i] = dir[i] == 1 ? lb[i] : ub[i];
        }
        return new SupertrendResult(st, dir);
    }

    // ── ADX (Wilder) ─────────────────────────────────────────

    /**
     * Average Directional Index — trend-strength regime filter.
     * Values ≥ ~20-25 = trending; below = chop. Volume-free, so it works on
     * index candles (which report zero volume).
     */
    public static double[] adx(List<CandleDTO> candles, int period) {
        int n = candles.size();
        double[] res = new double[n];
        if (n < period + 1) return res;

        double[] tr = new double[n], plusDm = new double[n], minusDm = new double[n];
        for (int i = 1; i < n; i++) {
            double h = candles.get(i).getHigh(), l = candles.get(i).getLow();
            double ph = candles.get(i - 1).getHigh(), pl = candles.get(i - 1).getLow();
            double pc = candles.get(i - 1).getClose();
            tr[i] = Math.max(h - l, Math.max(Math.abs(h - pc), Math.abs(l - pc)));
            double up = h - ph, dn = pl - l;
            plusDm[i]  = (up > dn && up > 0) ? up : 0;
            minusDm[i] = (dn > up && dn > 0) ? dn : 0;
        }

        // Wilder smoothing
        double smTr = 0, smPlus = 0, smMinus = 0;
        for (int i = 1; i <= period; i++) { smTr += tr[i]; smPlus += plusDm[i]; smMinus += minusDm[i]; }

        double[] dx = new double[n];
        for (int i = period; i < n; i++) {
            if (i > period) {
                smTr    = smTr - smTr / period + tr[i];
                smPlus  = smPlus - smPlus / period + plusDm[i];
                smMinus = smMinus - smMinus / period + minusDm[i];
            }
            double pdi = smTr > 0 ? 100 * smPlus / smTr : 0;
            double mdi = smTr > 0 ? 100 * smMinus / smTr : 0;
            double sum = pdi + mdi;
            dx[i] = sum > 0 ? 100 * Math.abs(pdi - mdi) / sum : 0;
        }

        // ADX = Wilder-smoothed DX
        int first = Math.min(2 * period - 1, n - 1);
        double adxSeed = 0;
        for (int i = period; i <= first; i++) adxSeed += dx[i];
        res[first] = adxSeed / period;
        for (int i = first + 1; i < n; i++) {
            res[i] = (res[i - 1] * (period - 1) + dx[i]) / period;
        }
        return res;
    }

    // ── Rolling avg volume (excludes last/current candle) ────

    public static double rollingAvgVolume(List<CandleDTO> candles, int period) {
        int end = candles.size() - 1;
        int start = Math.max(0, end - period);
        long sum = 0; int count = 0;
        for (int i = start; i < end; i++) { sum += candles.get(i).getVolume(); count++; }
        return count > 0 ? (double) sum / count : 0;
    }

    // ── Helpers ───────────────────────────────────────────────

    public static double[] closes(List<CandleDTO> candles) {
        return candles.stream().mapToDouble(CandleDTO::getClose).toArray();
    }

    public static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    // ── Supertrend result holder ──────────────────────────────

    public static class SupertrendResult {
        public final double[] line;
        public final int[]    direction;
        public SupertrendResult(double[] line, int[] direction) {
            this.line = line; this.direction = direction;
        }
    }
}
