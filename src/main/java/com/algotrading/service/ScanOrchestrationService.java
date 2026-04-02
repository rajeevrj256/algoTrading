package com.algotrading.service;

/**
 * ScanOrchestrationService — the main trading loop.
 *
 * Coordinates: DataFeed → Strategy → Risk → Broker → Sheets → Notification
 * Called by the scheduler every N minutes during market hours.
 *
 * Implementation: ScanOrchestrationServiceImpl
 */
public interface ScanOrchestrationService {

    /** Run a full market scan for all configured symbols. */
    void runScan();

    /** Perform end-of-day square-off for all open positions. */
    void runEod();
}
