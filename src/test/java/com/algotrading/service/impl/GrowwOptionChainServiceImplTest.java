package com.algotrading.service.impl;

import com.algotrading.config.FnoProperties;
import com.algotrading.dto.TradeSignalDTO;
import com.algotrading.enums.InstrumentType;
import com.algotrading.enums.OptionType;
import com.algotrading.enums.SignalType;
import com.algotrading.enums.StrategyType;
import com.algotrading.service.DataFeedService;
import com.algotrading.service.GrowwAuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GrowwOptionChainServiceImplTest {

    private GrowwOptionChainServiceImpl service() {
        FnoProperties props = new FnoProperties();
        props.setEnabled(true);
        props.setExpiryDayEntryCutoffMin(0);      // keep tests deterministic on expiry days
        FnoProperties.Underlying nifty = new FnoProperties.Underlying();
        nifty.setLotSize(75);
        nifty.setStrikeStep(50);
        nifty.setExpiryDay(DayOfWeek.TUESDAY);
        nifty.setWeeklyExpiry(true);
        props.getUnderlyings().put("NIFTY", nifty);

        GrowwAuthService auth = mock(GrowwAuthService.class);
        when(auth.hasCredentials()).thenReturn(false);   // force synthetic pricing path

        GrowwOptionChainServiceImpl svc = new GrowwOptionChainServiceImpl(
                props, auth, new ObjectMapper(), mock(DataFeedService.class));
        ReflectionTestUtils.setField(svc, "capital", 100000.0);
        return svc;
    }

    @Test
    void buySignalOnIndexBecomesAtmCallWithLotSizedQuantity() {
        TradeSignalDTO indexSignal = TradeSignalDTO.builder()
                .symbol("NIFTY_50").signal(SignalType.BUY).strategy(StrategyType.INDEX_TREND)
                .entryPrice(24510.0).stopLoss(24460.0).target(24620.0)
                .quantity(4).confidence(0.75).rrRatio(2.2)
                .signalReason("test").build();

        Optional<TradeSignalDTO> converted = service().toOptionSignal(indexSignal);

        assertTrue(converted.isPresent());
        TradeSignalDTO opt = converted.get();
        assertEquals(InstrumentType.INDEX_OPTION, opt.getInstrumentType());
        assertEquals(OptionType.CE, opt.getOptionType());
        assertEquals(24500.0, opt.getStrike(), 0.0001);          // ATM round of 24510 to 50-step
        assertEquals(SignalType.BUY, opt.getSignal());           // long option, always a buy
        assertEquals("NIFTY_50", opt.getUnderlying());
        assertEquals(DayOfWeek.TUESDAY, opt.getExpiry().getDayOfWeek());
        assertFalse(opt.getExpiry().isBefore(LocalDate.now()));
        assertEquals(0, opt.getQuantity() % 75);                 // whole lots only
        assertTrue(opt.getLots() >= 1);
        assertTrue(opt.getEntryPrice() > 0);
        assertTrue(opt.getStopLoss() < opt.getEntryPrice());     // premium stop below entry
        assertTrue(opt.getTarget() > opt.getEntryPrice());
        assertEquals(24510.0, opt.getUnderlyingEntry(), 0.0001); // index frame preserved for exits
        assertEquals(24460.0, opt.getUnderlyingStop(), 0.0001);
        assertEquals(24620.0, opt.getUnderlyingTarget(), 0.0001);
        assertTrue(opt.getSymbol().startsWith("NIFTY"));
        assertTrue(opt.getSymbol().endsWith("24500CE"));
    }

    @Test
    void sellSignalOnIndexBecomesPut() {
        TradeSignalDTO indexSignal = TradeSignalDTO.builder()
                .symbol("NIFTY_50").signal(SignalType.SELL).strategy(StrategyType.VWAP_TREND)
                .entryPrice(24490.0).stopLoss(24540.0).target(24380.0)
                .quantity(4).confidence(0.72).rrRatio(2.2)
                .signalReason("test").build();

        Optional<TradeSignalDTO> converted = service().toOptionSignal(indexSignal);

        assertTrue(converted.isPresent());
        assertEquals(OptionType.PE, converted.get().getOptionType());
        assertEquals(SignalType.BUY, converted.get().getSignal());
        assertTrue(converted.get().getSymbol().endsWith("PE"));
    }

    @Test
    void nonIndexSymbolIsNotAnOptionUnderlying() {
        assertFalse(service().isOptionUnderlying("RELIANCE"));
        assertTrue(service().isOptionUnderlying("NIFTY_50"));
        assertTrue(service().isOptionUnderlying("NIFTY"));
    }
}
