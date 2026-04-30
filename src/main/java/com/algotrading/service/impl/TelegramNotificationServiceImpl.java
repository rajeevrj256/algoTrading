package com.algotrading.service.impl;

import com.algotrading.dto.AlertDTO;
import com.algotrading.dto.PositionDTO;
import com.algotrading.service.NotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

import static org.apache.logging.log4j.util.Strings.repeat;

@Slf4j
@Service
public class TelegramNotificationServiceImpl implements NotificationService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd-MMM HH:mm:ss");
    private static final String API = "https://api.telegram.org/bot";

    @Value("${notification.telegram.bot-token:}")
    private String botToken;

    @Value("${notification.telegram.chat-id:}")
    private String chatId;

    private final RestTemplate restTemplate = new RestTemplate();

    // ── NotificationService impl ──────────────────────────────

    @Override
    public void sendAlert(AlertDTO alert) {
        String msg = String.format("<b>%s %s</b>\n%s\n<i>%s IST</i>",
                emoji(alert.getType() != null ? alert.getType().name() : "CUSTOM"),
                alert.getTitle(), alert.getMessage(), now());
        printConsole(alert.getTitle(), alert.getMessage());
        send(msg);
    }

    @Override
    public void notifyTradeOpen(PositionDTO p) {
        String e = p.getSignal().name().equals("BUY") ? "🟢" : "🔴";
        String msg = String.format(
                "%s <b>TRADE OPENED</b>\n" +
                "Symbol  : <b>%s</b>\n" +
                "Side    : <b>%s</b>\n" +
                "Strategy: %s\n" +
                "Entry   : ₹%.2f × %d\n" +
                "Stop    : ₹%.2f\n" +
                "Target  : ₹%.2f\n" +
                "Reason  : <i>%s</i>\n" +
                "<i>%s IST</i>",
                e, p.getSymbol(), p.getSignal(),
                p.getStrategy() != null ? p.getStrategy().name() : "N/A",
                p.getEntryPrice(), p.getQuantity(),
                p.getStopLoss(), p.getTarget(),
                ns(p.getSignalReason()), now());
        printConsole("TRADE OPENED", p.getSymbol() + " " + p.getSignal() +
                " x" + p.getQuantity() + " @ ₹" + p.getEntryPrice());
        send(msg);
    }

    @Override
    public void notifyTradeClose(PositionDTO p) {
        String e = p.getPnl() >= 0 ? "💰" : "📉";
        String msg = String.format(
                "%s <b>TRADE CLOSED</b>\n" +
                "Symbol   : <b>%s</b>\n" +
                "Side     : %s | Strategy: %s\n" +
                "Entry    : ₹%.2f  →  Exit: ₹%.2f\n" +
                "Gross    : ₹%+.2f\n" +
                "Charges  : ₹%.2f\n" +
                "Net P&L  : <b>₹%+.2f (%.2f%%)</b>\n" +
                "Reason   : %s\n" +
                "<i>%s IST</i>",
                e, p.getSymbol(), p.getSignal(),
                p.getStrategy() != null ? p.getStrategy().name() : "N/A",
                p.getEntryPrice(), p.getExitPrice(),
                p.getGrossPnl(), p.getCharges(),
                p.getPnl(), p.getPnlPct(),
                ns(p.getExitReason()), now());
        printConsole("TRADE CLOSED",
                p.getSymbol() + " Net=₹" + String.format("%+.2f", p.getPnl())
                        + " | Charges=₹" + String.format("%.2f", p.getCharges())
                        + " | " + p.getExitReason());
        send(msg);
    }

    @Override
    public void notifyCircuitBreaker(double totalLoss) {
        String msg = String.format(
                "🚨 <b>CIRCUIT BREAKER TRIPPED</b> 🚨\n" +
                "Daily loss ₹%.2f exceeded the limit.\n" +
                "All trading halted for today.\n" +
                "<i>%s IST</i>",
                Math.abs(totalLoss), now());
        log.error("[Notify] *** CIRCUIT BREAKER ALERT *** Loss ₹{}", String.format("%.2f", totalLoss));
        printConsole("CIRCUIT BREAKER", "Loss ₹" + String.format("%.2f", Math.abs(totalLoss)));
        send(msg);
    }

    @Override
    public boolean isTelegramEnabled() {
        return botToken != null && !botToken.isEmpty()
                && chatId  != null && !chatId.isEmpty();
    }

    // ── Internal Telegram send — POST + JSON body ─────────────

    private void send(String text) {
        if (!isTelegramEnabled()) {
            log.debug("[Notify] Telegram not configured — message suppressed");
            return;
        }
        try {
            String url = API + botToken + "/sendMessage";

            Map<String, Object> body = new HashMap<>();
            body.put("chat_id",                  chatId);
            body.put("text",                     text);
            body.put("parse_mode",               "HTML");
            body.put("disable_web_page_preview", true);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            ResponseEntity<String> resp = restTemplate.exchange(
                    url, HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    String.class);

            if (!resp.getStatusCode().is2xxSuccessful()) {
                log.warn("[Notify] Telegram send failed: {}", resp.getBody());
            }
        } catch (Exception e) {
            log.warn("[Notify] Telegram send error: {}", e.getMessage());
        }
    }

    // ── Console print ─────────────────────────────────────────

    private void printConsole(String title, String detail) {
        String border = repeat("=", 50);
        System.out.println("\n" + border);
        System.out.printf("  %s  —  %s%n", title, now());
        System.out.printf("  %s%n", detail);
        System.out.println(border + "\n");
    }

    private String emoji(String type) {
        switch (type) {
            case "TRADE_OPENED":    return "📈";
            case "TRADE_CLOSED":    return "💰";
            case "CIRCUIT_BREAKER": return "🚨";
            case "HEALTH_FAIL":     return "❌";
            case "HEALTH_WARN":     return "⚠️";
            case "DAILY_SUMMARY":   return "📊";
            default:                return "🔔";
        }
    }

    private String now() { return LocalDateTime.now(IST).format(FMT); }
    private String ns(String s) { return s != null ? s : ""; }
}
