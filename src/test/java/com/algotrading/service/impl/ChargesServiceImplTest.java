package com.algotrading.service.impl;

import com.algotrading.enums.InstrumentType;
import com.algotrading.enums.SignalType;
import com.algotrading.model.TradeCharges;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChargesServiceImplTest {

    @Test
    void calculateIncludesBrokerageTaxesAndNetPnlForIntradayLong() {
        ChargesServiceImpl service = new ChargesServiceImpl();

        TradeCharges charges = service.calculate(SignalType.BUY, 100000.0, 101000.0, 1);

        assertEquals(201000.0, charges.getTurnover(), 0.0001);
        assertEquals(40.0, charges.getBrokerage(), 0.0001);
        assertEquals(25.25, charges.getStt(), 0.0001);
        assertEquals(6.5325, charges.getExchangeCharge(), 0.0001);
        assertEquals(8.37585, charges.getGst(), 0.0001);
        assertEquals(0.201, charges.getSebi(), 0.0001);
        assertEquals(3.0, charges.getStamp(), 0.0001);
        assertEquals(83.35935, charges.getTotalCharges(), 0.0001);
        assertEquals(1000.0, charges.getGrossPnl(), 0.0001);
        assertEquals(916.64065, charges.getNetPnl(), 0.0001);
    }

    @Test
    void calculateOptionUsesFlatBrokerageAndPremiumBasedStatutoryCharges() {
        ChargesServiceImpl service = new ChargesServiceImpl();

        // Long 1 lot NIFTY (75 units): buy premium ₹100, sell ₹120.
        TradeCharges charges = service.calculate(
                SignalType.BUY, 100.0, 120.0, 75, InstrumentType.INDEX_OPTION);

        double buyValue = 7500.0, sellValue = 9000.0, turnover = 16500.0;
        assertEquals(turnover, charges.getTurnover(), 0.0001);
        assertEquals(40.0, charges.getBrokerage(), 0.0001);                    // ₹20 flat × 2 orders
        assertEquals(sellValue * 0.001, charges.getStt(), 0.0001);            // 0.1% sell premium
        assertEquals(turnover * 0.0003503, charges.getExchangeCharge(), 0.0001);
        assertEquals(turnover * 0.000001, charges.getSebi(), 0.0001);
        double gst = 0.18 * (40.0 + turnover * 0.0003503 + turnover * 0.000001);
        assertEquals(gst, charges.getGst(), 0.0001);
        assertEquals(buyValue * 0.00003, charges.getStamp(), 0.0001);         // 0.003% buy premium
        assertEquals(1500.0, charges.getGrossPnl(), 0.0001);
        assertEquals(1500.0 - charges.getTotalCharges(), charges.getNetPnl(), 0.0001);
    }

    @Test
    void calculateWithEquityInstrumentMatchesLegacyModel() {
        ChargesServiceImpl service = new ChargesServiceImpl();

        TradeCharges legacy = service.calculate(SignalType.BUY, 100000.0, 101000.0, 1);
        TradeCharges typed  = service.calculate(SignalType.BUY, 100000.0, 101000.0, 1, InstrumentType.EQUITY);

        assertEquals(legacy.getTotalCharges(), typed.getTotalCharges(), 0.0001);
        assertEquals(legacy.getNetPnl(), typed.getNetPnl(), 0.0001);
    }
}
