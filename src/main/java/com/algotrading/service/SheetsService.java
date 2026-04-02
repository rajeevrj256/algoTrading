package com.algotrading.service;

import com.algotrading.dto.DailySummaryDTO;
import com.algotrading.dto.PositionDTO;

import java.util.List;

/**
 * SheetsService — writes trade data to Google Sheets.
 *
 * Tabs managed:
 *   "Trade Log"      — one row per closed trade with full signal detail
 *   "Open Positions" — live view with GOOGLEFINANCE P&L formula
 *   "Daily Summary"  — one row per trading day
 *
 * Implementation: GoogleSheetsServiceImpl
 */
public interface SheetsService {

    /** Append a closed trade to the "Trade Log" tab. */
    void logTrade(PositionDTO position);

    /** Refresh the "Open Positions" tab with current open trades. */
    void updateOpenPositions(List<PositionDTO> openPositions);

    /** Append or update today's row in the "Daily Summary" tab. */
    void updateDailySummary(DailySummaryDTO summary);

    /** Return true if the Google Sheets connection is healthy. */
    boolean isConnected();

    /** Return the URL of the configured Google Sheet. */
    String getSheetUrl();
}
