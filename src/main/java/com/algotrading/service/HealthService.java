package com.algotrading.service;

import com.algotrading.dto.HealthReportDTO;

/**
 * HealthService — monitors the health of the application's internal subsystems.
 *
 * Implementation: InternalHealthServiceImpl
 */
public interface HealthService {

    /** Run a full health check and return the result. */
    HealthReportDTO runCheck();

    /** Return the last cached health report without re-running checks. */
    HealthReportDTO getLastReport();

    /** Total number of health checks run since startup. */
    int getCheckCount();
}
