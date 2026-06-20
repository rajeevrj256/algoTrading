package com.algotrading.service.impl;

import com.algotrading.dto.CandleDTO;
import com.algotrading.dto.TradeSignalDTO;
import com.algotrading.enums.SignalType;
import com.algotrading.enums.StrategyType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrbRetestStrategyTest {

    private final OrbRetestStrategy strategy = new OrbRetestStrategy();

    @Test
    void generateReturnsLongSignalForCleanOrbRetestContinuation() {
        List<CandleDTO> candles = buildLongSetupCandles(1800);

        Optional<TradeSignalDTO> signal = strategy.generate("INFY", candles);

        assertTrue(signal.isPresent());
        assertEquals(StrategyType.ORB_RETEST, signal.get().getStrategy());
        assertEquals(SignalType.BUY, signal.get().getSignal());
        assertTrue(signal.get().getEntryPrice() > signal.get().getStopLoss());
        assertTrue(signal.get().getTarget() > signal.get().getEntryPrice());
        assertTrue(signal.get().getConfidence() >= 0.74);
        assertTrue(signal.get().getRrRatio() >= 2.39);
    }

    @Test
    void generateSkipsSetupWhenSignalVolumeIsWeak() {
        List<CandleDTO> candles = buildLongSetupCandles(900);

        Optional<TradeSignalDTO> signal = strategy.generate("INFY", candles);

        assertFalse(signal.isPresent());
    }

    private List<CandleDTO> buildLongSetupCandles(long signalVolume) {
        List<CandleDTO> candles = new ArrayList<CandleDTO>();
        LocalDate previousDay = LocalDate.of(2026, 4, 29);
        LocalDate sessionDay = LocalDate.of(2026, 4, 30);

        double close = 100.0;
        for (int i = 0; i < 32; i++) {
            double drift = (i % 6 == 0 && i > 0) ? -0.04 : 0.14;
            double open = close - 0.06;
            close += drift;
            double high = Math.max(open, close) + 0.18;
            double low = Math.min(open, close) - 0.18;
            candles.add(candle("INFY",
                    LocalDateTime.of(previousDay, LocalTime.of(12, 0).plusMinutes(i * 5L)),
                    open, high, low, close, 980 + (i % 4) * 30));
        }

        candles.add(candle("INFY", LocalDateTime.of(sessionDay, LocalTime.of(9, 15)), 103.60, 104.20, 103.40, 104.00, 900));
        candles.add(candle("INFY", LocalDateTime.of(sessionDay, LocalTime.of(9, 20)), 104.00, 104.50, 103.80, 104.30, 920));
        candles.add(candle("INFY", LocalDateTime.of(sessionDay, LocalTime.of(9, 25)), 104.30, 104.80, 104.10, 104.60, 940));
        candles.add(candle("INFY", LocalDateTime.of(sessionDay, LocalTime.of(9, 30)), 104.60, 105.00, 104.40, 104.90, 960));
        candles.add(candle("INFY", LocalDateTime.of(sessionDay, LocalTime.of(9, 35)), 104.90, 105.60, 104.80, 105.40, 1100));
        candles.add(candle("INFY", LocalDateTime.of(sessionDay, LocalTime.of(9, 40)), 105.40, 105.90, 105.20, 105.70, 1150));
        candles.add(candle("INFY", LocalDateTime.of(sessionDay, LocalTime.of(9, 45)), 105.70, 105.80, 104.95, 105.25, 900));
        candles.add(candle("INFY", LocalDateTime.of(sessionDay, LocalTime.of(9, 50)), 105.25, 106.05, 105.20, 105.95, signalVolume));

        return candles;
    }

    private CandleDTO candle(String symbol,
                             LocalDateTime timestamp,
                             double open,
                             double high,
                             double low,
                             double close,
                             long volume) {
        return CandleDTO.builder()
                .symbol(symbol)
                .timestamp(timestamp)
                .open(open)
                .high(high)
                .low(low)
                .close(close)
                .volume(volume)
                .build();
    }
}
