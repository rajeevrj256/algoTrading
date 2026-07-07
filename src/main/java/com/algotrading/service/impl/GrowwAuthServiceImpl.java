package com.algotrading.service.impl;

import com.algotrading.service.GrowwAuthService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * GrowwAuthServiceImpl — implements GrowwAuthService.
 *
 * Token flow extracted from GrowFinanceDataFeedServiceImpl so the FNO option
 * chain service can share the same daily token and request throttle.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GrowwAuthServiceImpl implements GrowwAuthService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    // Token generation is meant to happen ~once/day. On failure, back off this long before re-attempting.
    private static final long TOKEN_FAIL_BACKOFF_SECONDS = 300;

    @Value("${groww.base-url:https://api.groww.in}")
    private String baseUrl;

    @Value("${groww.access-token:}")
    private String configuredAccessToken;

    @Value("${groww.api-key:}")
    private String apiKey;

    @Value("${groww.api-secret:}")
    private String apiSecret;

    @Value("${groww.api-version:1.0}")
    private String apiVersion;

    @Value("${groww.min-request-gap-ms:150}")
    private long minRequestGapMs;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    private volatile String currentAccessToken;
    private volatile Instant tokenExpiry;
    private volatile Instant tokenRetryAfter;   // backoff after a failed exchange (avoid hammering token endpoint)
    private volatile long lastRequestAtMs;

    @Override
    public boolean hasCredentials() {
        return (configuredAccessToken != null && !configuredAccessToken.isEmpty())
                || (apiKey != null && !apiKey.isEmpty() && apiSecret != null && !apiSecret.isEmpty());
    }

    @Override
    public ResponseEntity<String> get(String url) {
        // Fail fast when we have no token — sending an unauthenticated request just
        // earns a guaranteed 401 and (previously) triggered another token-exchange storm.
        if (getAccessToken() == null) {
            throw new IllegalStateException("No Groww access token available — token exchange failing");
        }
        try {
            throttleRequestRate();
            return restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(authHeaders()), String.class);
        } catch (HttpClientErrorException.Unauthorized e) {
            // 401 = token expired/rejected → refresh once and retry. (NOT 403: forbidden is an
            // access/permission problem, refreshing won't help and retrying only amplifies load.)
            log.info("[Groww] Token rejected (401), refreshing once and retrying");
            currentAccessToken = null;
            tokenExpiry = null;
            if (getAccessToken() == null) {
                throw new IllegalStateException("Groww token refresh failed after 401");
            }
            throttleRequestRate();
            return restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(authHeaders()), String.class);
        }
    }

    @Override
    public synchronized String getAccessToken() {
        if (configuredAccessToken != null && !configuredAccessToken.isEmpty()) {
            return configuredAccessToken;
        }
        if (currentAccessToken != null && tokenExpiry != null && Instant.now().isBefore(tokenExpiry)) {
            return currentAccessToken;
        }
        if (apiKey == null || apiKey.isEmpty() || apiSecret == null || apiSecret.isEmpty()) {
            log.warn("[Groww] No api-key/api-secret configured and no access-token set");
            return null;
        }
        if (tokenRetryAfter != null && Instant.now().isBefore(tokenRetryAfter)) {
            log.debug("[Groww] In token-exchange backoff until {}, skipping exchange", tokenRetryAfter);
            return null;
        }
        return exchangeForAccessToken();
    }

    private String exchangeForAccessToken() {
        try {
            String timestamp = String.valueOf(Instant.now().getEpochSecond());
            String checksum = sha256Hex(apiSecret + timestamp);

            Map<String, String> body = new HashMap<>();
            body.put("key_type", "approval");
            body.put("checksum", checksum);
            body.put("timestamp", timestamp);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
            headers.setBearerAuth(apiKey);

            ResponseEntity<String> resp = restTemplate.exchange(
                    baseUrl + "/v1/token/api/access",
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    String.class);

            JsonNode root = objectMapper.readTree(resp.getBody());
            String token = root.path("token").asText(null);
            if (token == null || token.isEmpty()) {
                log.error("[Groww] Token exchange returned no token: {}", resp.getBody());
                backoffTokenExchange();
                return null;
            }
            currentAccessToken = token;
            // Groww access tokens expire daily at 6 AM IST — cache until then.
            tokenExpiry = nextExpiry();
            tokenRetryAfter = null;
            log.info("[Groww] Access token refreshed, valid until {}", tokenExpiry);
            return token;
        } catch (Exception e) {
            // Includes 429 (rate limit) and 4xx — back off so we don't hammer the token endpoint.
            log.error("[Groww] Token exchange failed: {}", e.getMessage());
            backoffTokenExchange();
            return null;
        }
    }

    private void backoffTokenExchange() {
        tokenRetryAfter = Instant.now().plusSeconds(TOKEN_FAIL_BACKOFF_SECONDS);
        log.warn("[Groww] Backing off token exchange until {}", tokenRetryAfter);
    }

    private Instant nextExpiry() {
        LocalDateTime now = LocalDateTime.now(IST);
        LocalDateTime sixAm = now.toLocalDate().atTime(6, 0);
        if (!now.isBefore(sixAm)) sixAm = sixAm.plusDays(1);
        return sixAm.atZone(IST).toInstant();
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private HttpHeaders authHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        String token = getAccessToken();
        if (token != null) h.setBearerAuth(token);
        h.set("X-API-VERSION", apiVersion);
        return h;
    }

    private synchronized void throttleRequestRate() {
        if (minRequestGapMs <= 0) return;

        long now = System.currentTimeMillis();
        long waitMs = (lastRequestAtMs + minRequestGapMs) - now;
        if (waitMs > 0) {
            try {
                Thread.sleep(waitMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        lastRequestAtMs = System.currentTimeMillis();
    }
}
