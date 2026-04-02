package com.algotrading.service.impl;

import com.algotrading.dto.DailySummaryDTO;
import com.algotrading.dto.PositionDTO;
import com.algotrading.enums.SignalType;
import com.algotrading.service.SheetsService;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.SheetsScopes;
import com.google.api.services.sheets.v4.model.*;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.ServiceAccountCredentials;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.FileInputStream;
import java.util.*;

/**
 * GoogleSheetsServiceImpl — implements SheetsService.
 *
 * Writes trade data to Google Sheets using the Sheets API v4.
 * Gracefully degrades (logs warning, does not throw) if not configured.
 *
 * Required setup:
 *   1. Enable Google Sheets API in Google Cloud Console
 *   2. Create service account → download JSON key
 *   3. Share the sheet with the service account email
 *   4. Set sheets.sheet-id and sheets.service-account-file in application.yml
 */
@Slf4j
@Service
public class GoogleSheetsServiceImpl implements SheetsService {

    private static final List<String> TRADE_HEADERS = Arrays.asList(
            "Date","Time","Symbol","Side","Strategy","Entry","Exit","Stop","Target",
            "Qty","Risk(₹)","Reward(₹)","P&L(₹)","P&L%","R:R","Exit Reason",
            "Hold Duration","── WHY ──","Signal Reason","RSI","EMA Gap","VWAP Dev",
            "Vol Ratio","ATR","Extra","Why Full","Status");

    private static final List<String> OPEN_HEADERS = Arrays.asList(
            "Symbol","Side","Strategy","Entry","Stop","Target","Qty","Entry Time","Live P&L","Reason");

    private static final List<String> DAILY_HEADERS = Arrays.asList(
            "Date","Strategy","Trades","Wins","Losses","Win%","Total P&L","Best","Worst","Avg P&L");

    @Value("${sheets.sheet-id:}")
    private String sheetId;

    @Value("${sheets.service-account-file:service_account.json}")
    private String serviceAccountFile;

    private Sheets client;
    private boolean connected = false;
    private int nextRow = 2;

    @PostConstruct
    public void init() {
        if (sheetId == null || sheetId.isEmpty() || sheetId.equals("YOUR_SHEET_ID_HERE")) {
            log.warn("[Sheets] sheets.sheet-id not configured — Google Sheets logging disabled");
            return;
        }
        try {
            ServiceAccountCredentials creds = (ServiceAccountCredentials) ServiceAccountCredentials
                    .fromStream(new FileInputStream(serviceAccountFile))
                    .createScoped(Collections.singletonList(SheetsScopes.SPREADSHEETS));
            client = new Sheets.Builder(
                    GoogleNetHttpTransport.newTrustedTransport(),
                    GsonFactory.getDefaultInstance(),
                    new HttpCredentialsAdapter(creds))
                    .setApplicationName("AlgoTradingMonolith").build();

            setupTabs();
            connected = true;
            log.info("[Sheets] Connected — sheet: https://docs.google.com/spreadsheets/d/{}", sheetId);
        } catch (Exception e) {
            log.warn("[Sheets] Init failed: {} — Sheets logging disabled", e.getMessage());
        }
    }

    // ── SheetsService impl ────────────────────────────────────

    @Override
    public void logTrade(PositionDTO pos) {
        if (!connected) return;
        try {
            double riskAmt   = Math.abs(pos.getEntryPrice() - pos.getStopLoss())   * pos.getQuantity();
            double rewardAmt = Math.abs(pos.getTarget()     - pos.getEntryPrice())  * pos.getQuantity();
            double rr        = riskAmt > 0 ? r2(rewardAmt / riskAmt) : 0;
            String held      = pos.getExitTime() != null && pos.getEntryTime() != null
                    ? java.time.Duration.between(pos.getEntryTime(), pos.getExitTime()).toString() : "N/A";

            List<Object> row = Arrays.asList(
                    pos.getEntryTime() != null ? pos.getEntryTime().toLocalDate().toString() : "",
                    pos.getEntryTime() != null ? pos.getEntryTime().toLocalTime().toString()  : "",
                    pos.getSymbol(), pos.getSignal().name(),
                    pos.getStrategy() != null ? pos.getStrategy().name() : "",
                    pos.getEntryPrice(), pos.getExitPrice(), pos.getStopLoss(), pos.getTarget(),
                    pos.getQuantity(), r2(riskAmt), r2(rewardAmt), r2(pos.getPnl()), r2(pos.getPnlPct()),
                    rr, ns(pos.getExitReason()), held,
                    "─────────────────",
                    ns(pos.getSignalReason()), ns(pos.getIndRsi()), ns(pos.getIndEmaGap()),
                    ns(pos.getIndVwapDev()), ns(pos.getIndVolRatio()), ns(pos.getIndAtr()),
                    ns(pos.getIndExtra()), ns(pos.getWhyFull()), "CLOSED");

            append("Trade Log", row);
            colorRow("Trade Log", nextRow, pos.getPnl() >= 0);
            nextRow++;
            log.info("[Sheets] Trade logged row {} | {} P&L=₹{}", nextRow - 1, pos.getSymbol(), r2(pos.getPnl()));
        } catch (Exception e) { log.error("[Sheets] logTrade failed: {}", e.getMessage()); }
    }

    @Override
    public void updateOpenPositions(List<PositionDTO> openPositions) {
        if (!connected) return;
        try {
            clearRange("Open Positions!A2:J200");
            if (openPositions.isEmpty()) return;
            List<List<Object>> rows = new ArrayList<>();
            for (PositionDTO p : openPositions) {
                String ticker = p.getSymbol() + ".NS";
                String pnlF   = p.getSignal() == SignalType.BUY
                        ? String.format("=IFERROR((GOOGLEFINANCE(\"%s\",\"price\")-%.2f)*%d,\"refresh\")", ticker, p.getEntryPrice(), p.getQuantity())
                        : String.format("=IFERROR((%.2f-GOOGLEFINANCE(\"%s\",\"price\"))*%d,\"refresh\")", p.getEntryPrice(), ticker, p.getQuantity());
                rows.add(Arrays.asList(p.getSymbol(), p.getSignal().name(),
                        p.getStrategy() != null ? p.getStrategy().name() : "",
                        p.getEntryPrice(), p.getStopLoss(), p.getTarget(), p.getQuantity(),
                        p.getEntryTime() != null ? p.getEntryTime().toLocalTime().toString() : "",
                        pnlF, ns(p.getSignalReason())));
            }
            client.spreadsheets().values()
                    .update(sheetId, "Open Positions!A2",
                            new ValueRange().setValues(rows))
                    .setValueInputOption("USER_ENTERED").execute();
            log.info("[Sheets] Open positions updated — {}", openPositions.size());
        } catch (Exception e) { log.error("[Sheets] updateOpenPositions failed: {}", e.getMessage()); }
    }

    @Override
    public void updateDailySummary(DailySummaryDTO s) {
        if (!connected) return;
        try {
            List<Object> row = Arrays.asList(s.getDate(), s.getStrategy(),
                    s.getTrades(), s.getWins(), s.getLosses(), r2(s.getWinRate()),
                    r2(s.getTotalPnl()), r2(s.getBestTrade()), r2(s.getWorstTrade()), r2(s.getAvgPnl()));
            ValueRange existing = client.spreadsheets().values().get(sheetId, "Daily Summary!A:A").execute();
            List<List<Object>> dates = existing.getValues();
            int targetRow = -1;
            if (dates != null) {
                for (int i = 1; i < dates.size(); i++) {
                    if (!dates.get(i).isEmpty() && s.getDate().equals(dates.get(i).get(0).toString())) {
                        targetRow = i + 1; break;
                    }
                }
            }
            ValueRange body = new ValueRange().setValues(Collections.singletonList(row));
            if (targetRow > 0)
                client.spreadsheets().values().update(sheetId, "Daily Summary!A" + targetRow, body).setValueInputOption("USER_ENTERED").execute();
            else
                client.spreadsheets().values().append(sheetId, "Daily Summary!A1", body).setValueInputOption("USER_ENTERED").execute();
            log.info("[Sheets] Daily summary updated P&L=₹{}", r2(s.getTotalPnl()));
        } catch (Exception e) { log.error("[Sheets] updateDailySummary failed: {}", e.getMessage()); }
    }

    @Override public boolean isConnected() { return connected; }

    @Override
    public String getSheetUrl() { return "https://docs.google.com/spreadsheets/d/" + sheetId; }

    // ── Tab setup ─────────────────────────────────────────────

    private void setupTabs() throws Exception {
        Spreadsheet ss = client.spreadsheets().get(sheetId).execute();
        List<String> existing = new ArrayList<>();
        ss.getSheets().forEach(s -> existing.add(s.getProperties().getTitle()));
        ensureTab("Trade Log",      TRADE_HEADERS,  existing);
        ensureTab("Open Positions", OPEN_HEADERS,   existing);
        ensureTab("Daily Summary",  DAILY_HEADERS,  existing);
        ValueRange v = client.spreadsheets().values().get(sheetId, "Trade Log!A:A").execute();
        nextRow = Math.max(2, v.getValues() != null ? v.getValues().size() + 1 : 2);
    }

    private void ensureTab(String name, List<String> headers, List<String> existing) throws Exception {
        if (!existing.contains(name)) {
            client.spreadsheets().batchUpdate(sheetId,
                    new BatchUpdateSpreadsheetRequest().setRequests(Collections.singletonList(
                            new Request().setAddSheet(new AddSheetRequest()
                                    .setProperties(new SheetProperties().setTitle(name)))))).execute();
        }
        client.spreadsheets().values()
                .update(sheetId, name + "!A1", new ValueRange().setValues(Collections.singletonList(new ArrayList<>(headers))))
                .setValueInputOption("USER_ENTERED").execute();
        log.info("[Sheets] Tab '{}' ready", name);
    }

    private void append(String tab, List<Object> row) throws Exception {
        client.spreadsheets().values()
                .append(sheetId, tab + "!A" + nextRow, new ValueRange().setValues(Collections.singletonList(row)))
                .setValueInputOption("USER_ENTERED").execute();
    }

    private void colorRow(String tab, int row, boolean profit) {
        try {
            Spreadsheet ss = client.spreadsheets().get(sheetId).execute();
            int tabId = ss.getSheets().stream()
                    .filter(s -> tab.equals(s.getProperties().getTitle()))
                    .mapToInt(s -> s.getProperties().getSheetId()).findFirst().orElse(0);
            Color c = profit
                    ? new Color().setRed(0.85f).setGreen(0.95f).setBlue(0.85f)
                    : new Color().setRed(0.98f).setGreen(0.87f).setBlue(0.87f);
            client.spreadsheets().batchUpdate(sheetId,
                    new BatchUpdateSpreadsheetRequest().setRequests(Collections.singletonList(
                            new Request().setRepeatCell(new RepeatCellRequest()
                                    .setRange(new GridRange().setSheetId(tabId).setStartRowIndex(row - 1).setEndRowIndex(row))
                                    .setCell(new CellData().setUserEnteredFormat(new CellFormat().setBackgroundColor(c)))
                                    .setFields("userEnteredFormat.backgroundColor"))))).execute();
        } catch (Exception e) { log.warn("[Sheets] colorRow failed: {}", e.getMessage()); }
    }

    private void clearRange(String range) throws Exception {
        client.spreadsheets().values().clear(sheetId, range, new ClearValuesRequest()).execute();
    }

    private String ns(String s)   { return s != null ? s : ""; }
    private double r2(double v)   { return Math.round(v * 100.0) / 100.0; }
}
