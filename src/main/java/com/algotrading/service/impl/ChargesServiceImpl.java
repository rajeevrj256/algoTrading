package com.algotrading.service.impl;

import com.algotrading.enums.SignalType;
import com.algotrading.model.TradeCharges;
import com.algotrading.service.ChargesService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * ChargesServiceImpl — models Indian cash-equity brokerage and statutory costs.
 */
@Service
public class ChargesServiceImpl implements ChargesService {

    @Value("${charges.intraday:true}")
    private boolean intraday = true;

    @Value("${charges.brokerage-rate:0.0003}")
    private double brokerageRate = 0.0003;

    @Value("${charges.brokerage-cap-per-order:20}")
    private double brokerageCapPerOrder = 20.0;

    @Value("${charges.stt-intraday-sell-rate:0.00025}")
    private double sttIntradaySellRate = 0.00025;

    @Value("${charges.stt-delivery-buy-rate:0.001}")
    private double sttDeliveryBuyRate = 0.001;

    @Value("${charges.stt-delivery-sell-rate:0.001}")
    private double sttDeliverySellRate = 0.001;

    @Value("${charges.exchange-rate:0.0000325}")
    private double exchangeRate = 0.0000325;

    @Value("${charges.gst-rate:0.18}")
    private double gstRate = 0.18;

    @Value("${charges.sebi-rate:0.000001}")
    private double sebiRate = 0.000001;

    @Value("${charges.stamp-intraday-buy-rate:0.00003}")
    private double stampIntradayBuyRate = 0.00003;

    @Value("${charges.stamp-delivery-buy-rate:0.00015}")
    private double stampDeliveryBuyRate = 0.00015;

    @Override
    public TradeCharges calculate(SignalType signal, double entryPrice, double exitPrice, int quantity) {
        int safeQty = Math.max(0, quantity);
        double entryValue = Math.max(0, entryPrice) * safeQty;
        double exitValue = Math.max(0, exitPrice) * safeQty;

        double buyValue = signal == SignalType.SELL ? exitValue : entryValue;
        double sellValue = signal == SignalType.SELL ? entryValue : exitValue;
        double turnover = buyValue + sellValue;
        double brokerage = cappedBrokerage(buyValue) + cappedBrokerage(sellValue);
        double stt = intraday
                ? sellValue * sttIntradaySellRate
                : (buyValue * sttDeliveryBuyRate) + (sellValue * sttDeliverySellRate);
        double exchangeCharge = turnover * exchangeRate;
        double gst = gstRate * (brokerage + exchangeCharge);
        double sebi = turnover * sebiRate;
        double stamp = buyValue * (intraday ? stampIntradayBuyRate : stampDeliveryBuyRate);
        double totalCharges = brokerage + stt + exchangeCharge + gst + sebi + stamp;
        double grossPnl = signal == SignalType.SELL
                ? (entryValue - exitValue)
                : (exitValue - entryValue);

        return TradeCharges.builder()
                .buyValue(buyValue)
                .sellValue(sellValue)
                .turnover(turnover)
                .brokerage(brokerage)
                .stt(stt)
                .exchangeCharge(exchangeCharge)
                .gst(gst)
                .sebi(sebi)
                .stamp(stamp)
                .totalCharges(totalCharges)
                .grossPnl(grossPnl)
                .netPnl(grossPnl - totalCharges)
                .build();
    }

    private double cappedBrokerage(double tradeValue) {
        return Math.min(tradeValue * brokerageRate, brokerageCapPerOrder);
    }
}
