package com.algotrading.scheduler;

import com.algotrading.service.ScanOrchestrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.ZoneId;

/**
 * TradingScheduler — fires the market scan on a fixed interval.
 *
 * Scan: every 5 minutes during market hours (09:15–15:25 IST).
 * EOD:  daily at 15:35 IST Monday–Friday.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TradingScheduler {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final ScanOrchestrationService orchestrationService;

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
        LocalTime now   = LocalTime.now(IST);
        LocalTime open  = LocalTime.of(9, 15);
        LocalTime close = LocalTime.of(15, 25);

        if (now.isBefore(open) || now.isAfter(close)) {
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
     * EOD square-off — 15:35 IST, Monday–Friday.
     */
    @Scheduled(cron = "0 35 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void scheduledEod() {
        log.info("[Scheduler] Triggering EOD @ {}", LocalTime.now(IST));
        try {
            orchestrationService.runEod();
        } catch (Exception e) {
            log.error("[Scheduler] EOD failed: {}", e.getMessage(), e);
        }
    }
}
