package com.algotrading.service.impl;

import com.algotrading.config.FnoProperties;
import com.algotrading.dto.PositionDTO;
import com.algotrading.dto.TradeSignalDTO;
import com.algotrading.enums.InstrumentType;
import com.algotrading.enums.OptionType;
import com.algotrading.enums.SignalType;
import com.algotrading.model.OptionContract;
import com.algotrading.service.DataFeedService;
import com.algotrading.service.GrowwAuthService;
import com.algotrading.service.OptionChainService;
import com.algotrading.util.OptionPricing;
import com.algotrading.util.Symbols;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;
import java.util.Optional;

/**
 * GrowwOptionChainServiceImpl — implements OptionChainService.
 *
 * Resolves the tradable contract (nearest expiry, ATM ± offset strike), prices it
 * from the Groww FNO live-data endpoint, and falls back to a Black-Scholes
 * synthetic premium when the live quote is unavailable (paper trading never
 * blocks on a quote).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GrowwOptionChainServiceImpl implements OptionChainService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);
    private static final double MIN_PREMIUM = 1.0;   // don't trade sub-₹1 options (spread eats everything)

    private final FnoProperties fnoProperties;
    private final GrowwAuthService authService;
    private final ObjectMapper objectMapper;
    private final DataFeedService dataFeedService;

    /**
     * Set after Groww returns 403 on FNO market data (free plan has no data
     * scope). Stops re-hammering a request that can never succeed — synthetic
     * Black-Scholes pricing takes over until restart.
     */
    private volatile boolean fnoDataForbidden;

    @Value("${groww.base-url:https://api.groww.in}")
    private String baseUrl;

    @Value("${risk.capital:100000}")
    private double capital;

    // ── OptionChainService impl ───────────────────────────────

    @Override
    public boolean isFnoEnabled() {
        return fnoProperties.isEnabled();
    }

    @Override
    public boolean isOptionUnderlying(String scanSymbol) {
        String canonical = Symbols.canonicalIndex(scanSymbol);
        return canonical != null && fnoProperties.getUnderlyings().containsKey(canonical);
    }

    @Override
    public Optional<TradeSignalDTO> toOptionSignal(TradeSignalDTO s) {
        String canonical = Symbols.canonicalIndex(s.getSymbol());
        FnoProperties.Underlying spec = canonical == null ? null : fnoProperties.getUnderlyings().get(canonical);
        if (spec == null) return Optional.empty();

        LocalDateTime now = LocalDateTime.now(IST);
        LocalDate expiry = resolveExpiry(spec, now);
        if (expiry == null) return Optional.empty();

        // Theta crush guard: no fresh entries close to expiry-day close.
        if (expiry.equals(now.toLocalDate())
                && now.toLocalTime().isAfter(MARKET_CLOSE.minusMinutes(fnoProperties.getExpiryDayEntryCutoffMin()))) {
            log.info("[FnO] {} entry skipped — inside expiry-day cutoff window", canonical);
            return Optional.empty();
        }

        Optional<OptionContract> resolved = resolveContractAsOf(s.getSymbol(), s.getSignal(), s.getEntryPrice(), now);
        if (!resolved.isPresent()) return Optional.empty();
        OptionContract contract = resolved.get();
        OptionType type = contract.getOptionType();
        double spot = s.getEntryPrice();
        double strike = contract.getStrike();

        double tYears = yearsToExpiry(now, expiry);
        double premium = fetchLtp(contract.getTradingSymbol())
                .orElseGet(() -> syntheticPremium(type, spot, strike, tYears));
        if (premium < MIN_PREMIUM) {
            log.info("[FnO] {} rejected — premium ₹{} too small", contract.getTradingSymbol(), r2(premium));
            return Optional.empty();
        }

        // Delta maps index-frame SL/target distances into premium terms.
        double absDelta = clamp(Math.abs(OptionPricing.delta(type, spot, strike, tYears,
                fnoProperties.getSyntheticIv(), fnoProperties.getRiskFreeRate())), 0.30, 0.90);
        double underlyingRisk = Math.abs(s.getEntryPrice() - s.getStopLoss());
        double underlyingReward = Math.abs(s.getTarget() - s.getEntryPrice());
        if (underlyingRisk <= 0 || underlyingReward <= 0) return Optional.empty();

        double hardStop = premium * (1 - fnoProperties.getPremiumHardStopPct() / 100.0);
        double premiumStop = Math.max(r2(premium - absDelta * underlyingRisk), r2(hardStop));
        premiumStop = Math.max(premiumStop, 0.05);
        if (premiumStop >= premium) return Optional.empty();
        double premiumTarget = r2(premium + absDelta * underlyingReward);

        int lots = sizeOptionLots(premium, premiumStop, spec.getLotSize());
        if (lots <= 0) {
            log.info("[FnO] {} rejected — cannot size ≥1 lot within premium/risk caps", contract.getTradingSymbol());
            return Optional.empty();
        }
        int quantity = lots * spec.getLotSize();

        String optionInfo = String.format("%s %s %.0f %s exp %s | lots=%d x%d | Δ≈%.2f | prem ₹%.2f",
                canonical, contract.isMonthly() ? "MONTHLY" : "WEEKLY", strike, type, expiry, lots,
                spec.getLotSize(), absDelta, premium);

        return Optional.of(TradeSignalDTO.builder()
                .symbol(contract.getTradingSymbol())
                .signal(SignalType.BUY)                    // long option, always a buy
                .strategy(s.getStrategy())
                .entryPrice(r2(premium))
                .stopLoss(premiumStop)
                .target(premiumTarget)
                .quantity(quantity)
                .confidence(s.getConfidence())
                .rrRatio(s.getRrRatio())                   // index-frame R:R drives the risk gate
                .signalReason(s.getSignalReason() + " → " + optionInfo)
                .whyFull(s.getWhyFull())
                .indRsi(s.getIndRsi()).indEmaGap(s.getIndEmaGap()).indVwapDev(s.getIndVwapDev())
                .indVolRatio(s.getIndVolRatio()).indAtr(s.getIndAtr())
                .indExtra(optionInfo)
                .instrumentType(InstrumentType.INDEX_OPTION)
                .underlying(s.getSymbol())
                .optionType(type)
                .strike(strike)
                .expiry(expiry)
                .lotSize(spec.getLotSize())
                .lots(lots)
                .underlyingEntry(s.getEntryPrice())
                .underlyingStop(s.getStopLoss())
                .underlyingTarget(s.getTarget())
                .build());
    }

    @Override
    public Optional<Double> getOptionLtp(PositionDTO p) {
        Optional<Double> live = fetchLtp(p.getSymbol());
        if (live.isPresent()) return live;

        // Synthetic fallback: re-price from the live underlying spot.
        if (p.getOptionType() == null || p.getExpiry() == null || p.getStrike() <= 0) return Optional.empty();
        Optional<Double> spot = dataFeedService.getLastPrice(p.getUnderlying());
        if (!spot.isPresent()) return Optional.empty();
        double t = yearsToExpiry(LocalDateTime.now(IST), p.getExpiry());
        double premium = syntheticPremium(p.getOptionType(), spot.get(), p.getStrike(), t);
        log.debug("[FnO] {} synthetic LTP ₹{} (spot {})", p.getSymbol(), r2(premium), spot.get());
        return Optional.of(r2(premium));
    }

    // ── Contract resolution ───────────────────────────────────

    /**
     * Weekly underlyings → next expiry-day on/after today.
     * Monthly-only → last expiry-day of this month (roll to next month once passed).
     * A same-day expiry after market close rolls forward.
     */
    private LocalDate resolveExpiry(FnoProperties.Underlying spec, LocalDateTime now) {
        LocalDate today = now.toLocalDate();
        boolean afterClose = now.toLocalTime().isAfter(MARKET_CLOSE);

        if (spec.isWeeklyExpiry()) {
            LocalDate expiry = today.with(TemporalAdjusters.nextOrSame(spec.getExpiryDay()));
            if (expiry.equals(today) && afterClose) expiry = expiry.plusWeeks(1);
            return expiry;
        }

        LocalDate expiry = lastExpiryDayOfMonth(today, spec.getExpiryDay());
        if (expiry.isBefore(today) || (expiry.equals(today) && afterClose)) {
            expiry = lastExpiryDayOfMonth(today.plusMonths(1).withDayOfMonth(1), spec.getExpiryDay());
        }
        return expiry;
    }

    private LocalDate lastExpiryDayOfMonth(LocalDate anyDayInMonth, DayOfWeek day) {
        return anyDayInMonth.with(TemporalAdjusters.lastDayOfMonth()).with(TemporalAdjusters.previousOrSame(day));
    }

    /** ATM strike, shifted itmOffsetSteps INTO the money (CE lower / PE higher). */
    private double pickStrike(double spot, int step, OptionType type, int itmOffsetSteps) {
        double atm = Math.round(spot / step) * (double) step;
        double shift = itmOffsetSteps * (double) step;
        return type == OptionType.CE ? atm - shift : atm + shift;
    }

    private boolean isMonthlyContract(LocalDate expiry, FnoProperties.Underlying spec) {
        return expiry.equals(lastExpiryDayOfMonth(expiry, spec.getExpiryDay()));
    }

    /**
     * NSE option trading symbol.
     * Monthly : SYMBOL + yy + MMM + strike + CE/PE      (NIFTY25JUL24500CE)
     * Weekly  : SYMBOL + yy + M + dd + strike + CE/PE   (NIFTY2570824500CE)
     *           month code M = 1..9 (Jan-Sep), O, N, D (Oct, Nov, Dec)
     */
    private String buildTradingSymbol(String underlying, LocalDate expiry, double strike,
                                      OptionType type, FnoProperties.Underlying spec) {
        String yy = String.format("%02d", expiry.getYear() % 100);
        String strikeStr = String.valueOf((long) strike);
        if (isMonthlyContract(expiry, spec)) {
            String mmm = expiry.getMonth().getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH)
                    .toUpperCase(Locale.ENGLISH);
            return underlying + yy + mmm + strikeStr + type.name();
        }
        String monthCode;
        int m = expiry.getMonthValue();
        if (m <= 9)       monthCode = String.valueOf(m);
        else if (m == 10) monthCode = "O";
        else if (m == 11) monthCode = "N";
        else              monthCode = "D";
        return underlying + yy + monthCode + String.format("%02d", expiry.getDayOfMonth())
                + strikeStr + type.name();
    }

    @Override
    public Optional<OptionContract> resolveContractAsOf(String scanSymbol, SignalType direction,
                                                        double spot, LocalDateTime asOf) {
        String canonical = Symbols.canonicalIndex(scanSymbol);
        FnoProperties.Underlying spec = canonical == null ? null : fnoProperties.getUnderlyings().get(canonical);
        if (spec == null) return Optional.empty();

        LocalDate expiry = resolveExpiry(spec, asOf);
        if (expiry == null) return Optional.empty();

        OptionType type = direction == SignalType.BUY ? OptionType.CE : OptionType.PE;
        double strike = pickStrike(spot, spec.getStrikeStep(), type, fnoProperties.getItmOffsetSteps());

        return Optional.of(OptionContract.builder()
                .underlying(canonical)
                .scanSymbol(scanSymbol)
                .tradingSymbol(buildTradingSymbol(canonical, expiry, strike, type, spec))
                .optionType(type)
                .strike(strike)
                .expiry(expiry)
                .lotSize(spec.getLotSize())
                .monthly(isMonthlyContract(expiry, spec))
                .build());
    }

    // ── Pricing ───────────────────────────────────────────────

    private Optional<Double> fetchLtp(String tradingSymbol) {
        if (!authService.hasCredentials() || fnoDataForbidden) return Optional.empty();
        try {
            String exchangeSymbol = "NSE_" + tradingSymbol;
            String url = UriComponentsBuilder.fromHttpUrl(baseUrl + "/v1/live-data/ltp")
                    .queryParam("segment", "FNO")
                    .queryParam("exchange_symbols", exchangeSymbol)
                    .toUriString();
            ResponseEntity<String> resp = authService.get(url);
            JsonNode price = objectMapper.readTree(resp.getBody()).path("payload").path(exchangeSymbol);
            if (price.isMissingNode() || price.isNull() || price.asDouble() <= 0) return Optional.empty();
            return Optional.of(price.asDouble());
        } catch (org.springframework.web.client.HttpClientErrorException.Forbidden e) {
            // Free Groww plan: token is valid but has no market-data scope — every
            // data call 403s. Remember it, stop asking, price synthetically instead.
            fnoDataForbidden = true;
            log.warn("[FnO] Groww FNO market data FORBIDDEN (free plan has no data scope) — " +
                    "switching to synthetic Black-Scholes premiums until restart");
            return Optional.empty();
        } catch (Exception e) {
            log.warn("[FnO] Option LTP fetch failed for {}: {}", tradingSymbol, e.getMessage());
            return Optional.empty();
        }
    }

    private double syntheticPremium(OptionType type, double spot, double strike, double tYears) {
        return OptionPricing.blackScholes(type, spot, strike, tYears,
                fnoProperties.getSyntheticIv(), fnoProperties.getRiskFreeRate());
    }

    private double yearsToExpiry(LocalDateTime now, LocalDate expiry) {
        LocalDateTime expiryClose = expiry.atTime(MARKET_CLOSE);
        long minutes = Math.max(30, Duration.between(now, expiryClose).toMinutes());
        return minutes / (365.0 * 24 * 60);
    }

    // ── Sizing ────────────────────────────────────────────────

    /**
     * Lots sized so premium risk (entry − premium stop) ≈ fno.risk-per-trade-pct of
     * capital, clamped by max lots and max premium outlay. Options are lumpy:
     * when even 1 lot exceeds the target risk, still take the single lot as long
     * as it stays under fno.max-risk-per-trade-pct (hard cap). 0 = cannot trade.
     */
    @Override
    public int sizeOptionLots(double premium, double premiumStop, int lotSize) {
        double riskBudget = capital * fnoProperties.getRiskPerTradePct() / 100.0;
        double hardRiskCap = capital * fnoProperties.getMaxRiskPerTradePct() / 100.0;
        double perUnitRisk = premium - premiumStop;
        if (perUnitRisk <= 0) return 0;

        double perLotRisk = perUnitRisk * lotSize;
        int lots = (int) Math.floor(riskBudget / perLotRisk);
        if (lots == 0 && perLotRisk <= hardRiskCap) {
            lots = 1;   // minimum viable position within the hard cap
        }
        lots = Math.min(lots, fnoProperties.getMaxLotsPerTrade());

        // Premium outlay cap — long options tie up the full premium.
        while (lots > 0 && premium * lotSize * lots > fnoProperties.getMaxPremiumPerTrade()) {
            lots--;
        }
        return lots;
    }

    private double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
    private double r2(double v) { return Math.round(v * 100.0) / 100.0; }
}
