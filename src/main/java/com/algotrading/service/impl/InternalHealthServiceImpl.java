package com.algotrading.service.impl;

import com.algotrading.dto.HealthReportDTO;
import com.algotrading.dto.HealthReportDTO.CheckItemDTO;
import com.algotrading.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.apache.logging.log4j.util.Strings.repeat;

/**
 * InternalHealthServiceImpl — implements HealthService.
 *
 * Checks all internal subsystems every 15 minutes.
 * All checks are in-process (no HTTP calls needed — it's a monolith).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InternalHealthServiceImpl implements HealthService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final DataFeedService     dataFeedService;
    private final StrategyService     strategyService;
    private final RiskService         riskService;
    private final BrokerService       brokerService;
    private final SheetsService       sheetsService;
    private final NotificationService notificationService;

    @Value("${app.symbols:RELIANCE,TCS,INFY,HDFCBANK,ICICIBANK,AXISBANK,WIPRO,SBIN}")
    private String symbolsStr;

    private final AtomicInteger checkCount = new AtomicInteger(0);
    private volatile HealthReportDTO lastReport;

    @PostConstruct
    public void init() {
        lastReport = emptyReport();
    }

    @Scheduled(fixedRateString = "${health.check-interval-ms:900000}")
    public void scheduledCheck() {
        log.info("[Health] Running scheduled check #{}", checkCount.get() + 1);
        runCheck();
    }

    // ── HealthService impl ────────────────────────────────────

    @Override
    public HealthReportDTO runCheck() {
        int num = checkCount.incrementAndGet();
        List<CheckItemDTO> items = new ArrayList<>();

        items.add(checkProcess());
        items.add(checkDataFeed());
        items.add(checkStrategies());
        items.add(checkRisk());
        items.add(checkBroker());
        items.add(checkSheets());
        items.add(checkTelegram());
        items.add(checkMarketHours());

        long ok   = items.stream().filter(i -> "OK".equals(i.getStatus())).count();
        long warn = items.stream().filter(i -> "WARN".equals(i.getStatus())).count();
        long fail = items.stream().filter(i -> "FAIL".equals(i.getStatus())).count();
        String overall = fail > 0 ? "FAIL" : warn > 0 ? "WARN" : "OK";

        HealthReportDTO report = HealthReportDTO.builder()
                .timestamp(LocalDateTime.now(IST).format(FMT))
                .checkNumber(num).overallStatus(overall)
                .okCount((int) ok).warnCount((int) warn).failCount((int) fail)
                .items(items).build();

        lastReport = report;
        printReport(report);
        log.info("[Health] #{} — {} | OK:{} WARN:{} FAIL:{}", num, overall, ok, warn, fail);
        return report;
    }

    @Override public HealthReportDTO getLastReport() { return lastReport; }
    @Override public int             getCheckCount() { return checkCount.get(); }

    // ── Individual checks ─────────────────────────────────────

    private CheckItemDTO checkProcess() {
        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        return item("JVM Process", usedMb > 500 ? "WARN" : "OK",
                usedMb > 500 ? "High memory usage" : "Normal",
                usedMb + " MB used");
    }

    private CheckItemDTO checkDataFeed() {
        try {
            boolean ok = dataFeedService.isAvailable();
            return item("Data Feed (Yahoo Finance)", ok ? "OK" : "FAIL",
                    ok ? "Feed is reachable" : "Yahoo Finance unreachable — check internet",
                    ok ? "UP" : "DOWN");
        } catch (Exception e) {
            return item("Data Feed (Yahoo Finance)", "FAIL", e.getMessage(), "ERROR");
        }
    }

    private CheckItemDTO checkStrategies() {
        List<String> loaded = strategyService.listStrategies();
        boolean ok = loaded.size() == 5;
        return item("Strategy Engine", ok ? "OK" : "WARN",
                ok ? "All 5 strategies loaded" : "Expected 5, loaded " + loaded.size(),
                String.join(", ", loaded));
    }

    private CheckItemDTO checkRisk() {
        boolean tripped = riskService.isCircuitTripped();
        if (tripped)
            return item("Risk Manager", "FAIL", "CIRCUIT BREAKER IS TRIPPED — trading halted", "TRIPPED");
        double pnl = riskService.getDailySummary().getTotalPnl();
        return item("Risk Manager", "OK",
                String.format("P&L ₹%.2f | Trades %d | WR %.0f%%",
                        pnl, riskService.getDailySummary().getTrades(),
                        riskService.getDailySummary().getWinRate()),
                "NORMAL");
    }

    private CheckItemDTO checkBroker() {
        int open = brokerService.getOpenPositions().size();
        int orders = brokerService.getOrders().size();
        return item("Paper Broker", "OK",
                open + " open position(s) | " + orders + " orders today",
                "ACTIVE");
    }

    private CheckItemDTO checkSheets() {
        boolean conn = sheetsService.isConnected();
        return item("Database (PostgreSQL)", conn ? "OK" : "FAIL",
                conn ? "Connected — " + sheetsService.getSheetUrl()
                     : "Not connected — check datasource config",
                conn ? "CONNECTED" : "DOWN");
    }

    private CheckItemDTO checkTelegram() {
        boolean enabled = notificationService.isTelegramEnabled();
        return item("Telegram Alerts", enabled ? "OK" : "WARN",
                enabled ? "Bot configured and active"
                        : "Not configured — set bot-token and chat-id",
                enabled ? "ENABLED" : "DISABLED");
    }

    private CheckItemDTO checkMarketHours() {
        LocalDateTime now = LocalDateTime.now(IST);
        LocalTime t = now.toLocalTime();
        LocalTime open  = LocalTime.of(9, 15);
        LocalTime close = LocalTime.of(15, 30);
        if (t.isAfter(open) && t.isBefore(close)) {
            long mins = java.time.Duration.between(t, close).toMinutes();
            return item("Market Hours", "OK", "Market OPEN — closes in " + mins + " min", t.toString());
        } else if (t.isBefore(open)) {
            long mins = java.time.Duration.between(t, open).toMinutes();
            return item("Market Hours", "WARN", "Pre-market — opens in " + mins + " min", t.toString());
        }
        return item("Market Hours", "WARN", "Market CLOSED for today", t.toString());
    }

    // ── Console report ────────────────────────────────────────

    private void printReport(HealthReportDTO r) {
        String sep = repeat("═", 68);
        System.out.println("\n" + sep);
        System.out.printf("  HEALTH CHECK #%d  —  %s%n", r.getCheckNumber(), r.getTimestamp());
        System.out.println(sep);
        System.out.printf("  Overall: %-6s  (OK:%-2d WARN:%-2d FAIL:%-2d)%n",
                r.getOverallStatus(), r.getOkCount(), r.getWarnCount(), r.getFailCount());
        System.out.println("  " + repeat("─", 66));
        System.out.printf("  %-28s %-7s %-14s  %s%n", "CHECK", "STATUS", "VALUE", "DETAIL");
        System.out.println("  " + repeat("─", 66));
        for (CheckItemDTO i : r.getItems())
            System.out.printf("  %-28s %-7s %-14s  %s%n", i.getName(), i.getStatus(), i.getValue(), i.getDetail());
        System.out.println(sep + "\n");
    }

    private CheckItemDTO item(String name, String status, String detail, String value) {
        return CheckItemDTO.builder().name(name).status(status).detail(detail).value(value).build();
    }

    private HealthReportDTO emptyReport() {
        return HealthReportDTO.builder().timestamp("not yet run").checkNumber(0)
                .overallStatus("UNKNOWN").okCount(0).warnCount(0).failCount(0)
                .items(new ArrayList<>()).build();
    }
}
