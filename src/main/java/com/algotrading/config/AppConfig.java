package com.algotrading.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * AppConfig — defines infrastructure Spring beans.
 *
 * RestTemplate: pooled HTTP client for Yahoo Finance and Telegram API calls.
 * ObjectMapper: Java 8 time support (LocalDateTime, etc.)
 */
@Configuration
public class AppConfig {

    @Bean
    public RestTemplate restTemplate() {
        PoolingHttpClientConnectionManager connMgr = new PoolingHttpClientConnectionManager();
        connMgr.setMaxTotal(20);
        connMgr.setDefaultMaxPerRoute(10);

        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(connMgr)
                .build();

        HttpComponentsClientHttpRequestFactory factory =
                new HttpComponentsClientHttpRequestFactory(httpClient);
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(10_000);

        return new RestTemplate(factory);
    }

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }
}
