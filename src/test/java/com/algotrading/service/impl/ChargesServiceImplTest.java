package com.algotrading.service.impl;

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
}
