package com.algotrading.service;

import org.springframework.http.ResponseEntity;

/**
 * GrowwAuthService — owns the Groww API access token (daily SHA256 exchange flow)
 * and issues throttled, authenticated GET requests.
 *
 * Shared by GrowFinanceDataFeedServiceImpl (CASH candles/LTP) and
 * GrowwOptionChainServiceImpl (FNO option LTP).
 */
public interface GrowwAuthService {

    /** Valid access token, or null when credentials are missing / exchange is failing. */
    String getAccessToken();

    /** Throttled authenticated GET. Refreshes the token once on 401 and retries. */
    ResponseEntity<String> get(String url);

    /** True when an api-key/secret or static access token is configured. */
    boolean hasCredentials();
}
