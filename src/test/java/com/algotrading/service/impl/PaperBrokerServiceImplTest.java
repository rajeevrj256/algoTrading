package com.algotrading.service.impl;

import com.algotrading.dto.PositionDTO;
import com.algotrading.dto.TradeSignalDTO;
import com.algotrading.enums.SignalType;
import com.algotrading.repository.FnoTradeLogRepository;
import com.algotrading.repository.TradeLogRepository;
import com.algotrading.service.SheetsService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaperBrokerServiceImplTest {

    @Test
    void restoreOpenPositionsSeedsNextPositionIdFromHistoricalTrades() {
        SheetsService sheetsService = mock(SheetsService.class);
        TradeLogRepository tradeLogRepository = mock(TradeLogRepository.class);
        FnoTradeLogRepository fnoTradeLogRepository = mock(FnoTradeLogRepository.class);
        ChargesServiceImpl chargesService = new ChargesServiceImpl();

        when(sheetsService.loadOpenPositions()).thenReturn(Collections.emptyList());
        when(tradeLogRepository.findMaxPositionSequence()).thenReturn(27);
        when(tradeLogRepository.findByTradeDate(any(LocalDate.class))).thenReturn(Collections.emptyList());
        when(fnoTradeLogRepository.findByTradeDate(any(LocalDate.class))).thenReturn(Collections.emptyList());

        PaperBrokerServiceImpl brokerService = new PaperBrokerServiceImpl(chargesService, sheetsService, tradeLogRepository, fnoTradeLogRepository);
        brokerService.restoreOpenPositions();

        PositionDTO openedPosition = brokerService.openPosition(TradeSignalDTO.builder()
                .symbol("INFY")
                .signal(SignalType.BUY)
                .entryPrice(1500.0)
                .stopLoss(1490.0)
                .target(1520.0)
                .quantity(1)
                .build());

        assertEquals("POS-00028", openedPosition.getPositionId());
    }

    @Test
    void checkExitsClosesAtConfiguredStopLossInsteadOfLaterPolledPrice() {
        SheetsService sheetsService = mock(SheetsService.class);
        TradeLogRepository tradeLogRepository = mock(TradeLogRepository.class);
        FnoTradeLogRepository fnoTradeLogRepository = mock(FnoTradeLogRepository.class);
        ChargesServiceImpl chargesService = new ChargesServiceImpl();

        when(sheetsService.loadOpenPositions()).thenReturn(Collections.emptyList());
        when(tradeLogRepository.findByTradeDate(any(LocalDate.class))).thenReturn(Collections.emptyList());
        when(fnoTradeLogRepository.findByTradeDate(any(LocalDate.class))).thenReturn(Collections.emptyList());

        PaperBrokerServiceImpl brokerService = new PaperBrokerServiceImpl(chargesService, sheetsService, tradeLogRepository, fnoTradeLogRepository);

        PositionDTO openedPosition = brokerService.openPosition(TradeSignalDTO.builder()
                .symbol("INFY")
                .signal(SignalType.BUY)
                .entryPrice(1500.0)
                .stopLoss(1490.0)
                .target(1520.0)
                .quantity(1)
                .build());

        PositionDTO closedPosition = brokerService.checkExits("INFY", 1485.0).stream().findFirst().orElse(null);

        assertNotNull(closedPosition);
        assertEquals(openedPosition.getPositionId(), closedPosition.getPositionId());
        assertEquals(1490.0, closedPosition.getExitPrice(), 0.0001);
        assertEquals(-10.0, closedPosition.getGrossPnl(), 0.0001);
        assertEquals(1.59, closedPosition.getCharges(), 0.0001);
        assertEquals(-11.59, closedPosition.getPnl(), 0.0001);
        assertEquals("STOP LOSS @ ₹1490.00", closedPosition.getExitReason());
    }
}
