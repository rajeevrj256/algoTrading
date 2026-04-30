package com.algotrading.scheduler;

import com.algotrading.service.ScanOrchestrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.ZoneId;

/**
 * TradingScheduler — fires the market scan on a fixed interval.
 *
 * Exit checks: every N seconds for open positions during market hours.
 * Scan: every 5 minutes during market hours (09:15–15:25 IST).
 * EOD:  daily at 15:26 IST Monday–Friday.
 * Auto shutdown: optional, after market close.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TradingScheduler {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 15);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 25);

    private final ScanOrchestrationService orchestrationService;
    private final ConfigurableApplicationContext applicationContext;

    @Value("${app.auto-shutdown.enabled:true}")
    private boolean autoShutdownEnabled;

    /**
     * Fast exit checks — only for already open positions.
     */
    @Scheduled(
            fixedRateString = "${app.exit-check-interval-ms:30000}",
            initialDelayString = "${app.scan-initial-delay-ms:5000}"
    )
    public void scheduledExitCheck() {
        LocalTime now = LocalTime.now(IST);
        if (outsideMarketHours(now)) {
            log.debug("[Scheduler] Outside market hours ({}) — exit check skipped", now);
            return;
        }

        log.debug("[Scheduler] Triggering exit check @ {}", now);
        try {
            orchestrationService.runExitChecks();
        } catch (Exception e) {
            log.error("[Scheduler] Exit check failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Market scan — every 5 minutes.
     * Initial delay of 5 seconds gives Spring time to fully start up.
     * Change engine.scan-interval-ms in application.yml to adjust frequency.
     */
    @Scheduled(
            fixedRateString  = "${app.scan-interval-ms:300000}",
            initialDelayString = "${app.scan-initial-delay-ms:5000}"
    )
    public void scheduledScan() {
        LocalTime now = LocalTime.now(IST);
        if (outsideMarketHours(now)) {
            log.debug("[Scheduler] Outside market hours ({}) — scan skipped", now);
            return;
        }

        log.info("[Scheduler] Triggering scan @ {}", now);
        try {
            orchestrationService.runScan();
        } catch (Exception e) {
            log.error("[Scheduler] Scan failed: {}", e.getMessage(), e);
        }
    }

    /**
     * EOD square-off — 15:26 IST, Monday–Friday.
     */
    @Scheduled(cron = "0 26 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void scheduledEod() {
        log.info("[Scheduler] Triggering EOD @ {}", LocalTime.now(IST));
        try {
            orchestrationService.runEod();
        } catch (Exception e) {
            log.error("[Scheduler] EOD failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Auto shutdown — 15:27 IST, Monday–Friday.
     */
    @Scheduled(cron = "${app.auto-shutdown.cron:0 27 15 * * MON-FRI}", zone = "Asia/Kolkata")
    public void scheduledShutdown() {
        if (!autoShutdownEnabled) {
            return;
        }

        log.info("[Scheduler] Auto shutdown triggered @ {}", LocalTime.now(IST));
        new Thread(() -> {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            int exitCode = SpringApplication.exit(applicationContext, () -> 0);
            System.exit(exitCode);
        }, "auto-shutdown-thread").start();
    }

    private boolean outsideMarketHours(LocalTime now) {
        return now.isBefore(MARKET_OPEN) || now.isAfter(MARKET_CLOSE);
    }
}
