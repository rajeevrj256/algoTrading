package com.algotrading.service.impl;

import com.algotrading.dto.AlertDTO;
import com.algotrading.dto.PositionDTO;
import com.algotrading.service.NotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import static org.apache.logging.log4j.util.Strings.repeat;

/**
 * TelegramNotificationServiceImpl — implements NotificationService.
 *
 * Sends alerts to a Telegram bot. Falls back to console-only if not configured.
 *
 * Setup:
 *   1. Create a bot via @BotFather → copy BOT_TOKEN
 *   2. Start chat with bot, call getUpdates to find CHAT_ID
 *   3. Set notification.telegram.bot-token and notification.telegram.chat-id
 */
@Slf4j
@Service
public class TelegramNotificationServiceImpl implements NotificationService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd-MMM HH:mm:ss");

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
                "Symbol  : <b>%s</b>\nSide    : <b>%s</b>\nStrategy: %s\n" +
                "Entry   : ₹%.2f × %d\nStop    : ₹%.2f\nTarget  : ₹%.2f\n" +
                "Reason  : <i>%s</i>\n<i>%s IST</i>",
                e, p.getSymbol(), p.getSignal(),
                p.getStrategy() != null ? p.getStrategy().name() : "N/A",
                p.getEntryPrice(), p.getQuantity(), p.getStopLoss(), p.getTarget(),
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
                "Symbol   : <b>%s</b>\nSide     : %s | Strategy: %s\n" +
                "Entry    : ₹%.2f  →  Exit: ₹%.2f\n" +
                "P&amp;L  : <b>₹%+.2f (%.2f%%)</b>\n" +
                "Reason   : %s\n<i>%s IST</i>",
                e, p.getSymbol(), p.getSignal(),
                p.getStrategy() != null ? p.getStrategy().name() : "N/A",
                p.getEntryPrice(), p.getExitPrice(),
                p.getPnl(), p.getPnlPct(),
                ns(p.getExitReason()), now());
        printConsole("TRADE CLOSED",
                p.getSymbol() + " P&L=₹" + String.format("%+.2f", p.getPnl()) + " | " + p.getExitReason());
        send(msg);
    }

    @Override
    public void notifyCircuitBreaker(double totalLoss) {
        String msg = String.format(
                "🚨 <b>CIRCUIT BREAKER TRIPPED</b> 🚨\n" +
                "Daily loss ₹%.2f exceeded the limit.\n" +
                "All trading halted for today.\n<i>%s IST</i>",
                Math.abs(totalLoss), now());
        log.error("[Notify] *** CIRCUIT BREAKER ALERT *** Loss ₹{}", String.format("%.2f", totalLoss));
        printConsole("🚨 CIRCUIT BREAKER", "Loss ₹" + String.format("%.2f", Math.abs(totalLoss)));
        send(msg);
    }

    @Override
    public boolean isTelegramEnabled() {
        return botToken != null && !botToken.isEmpty()
                && chatId  != null && !chatId.isEmpty();
    }

    // ── Internal Telegram send ────────────────────────────────

    private void send(String text) {
        if (!isTelegramEnabled()) {
            log.debug("[Notify] Telegram not configured — message suppressed");
            return;
        }
        try {
            String encoded = URLEncoder.encode(text, StandardCharsets.UTF_8.name());
            String url = String.format(
                    "https://api.telegram.org/bot%s/sendMessage?chat_id=%s&text=%s&parse_mode=HTML",
                    botToken, chatId, encoded);
            restTemplate.getForObject(url, String.class);
        } catch (UnsupportedEncodingException e) {
            log.error("[Notify] Encoding error: {}", e.getMessage());
        } catch (Exception e) {
            log.warn("[Notify] Telegram send failed: {}", e.getMessage());
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
