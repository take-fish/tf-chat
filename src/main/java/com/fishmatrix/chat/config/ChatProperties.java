package com.fishmatrix.chat.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.chat")
public record ChatProperties(
        boolean relayEnabled,
        long presenceTtlMs,
        int historyLimit,
        int dmHistoryLimit,
        int offlineLimit,
        int maxContentLength,
        int maxNicknameLength,
        String userServiceUrl,
        int wsTicketTtlSeconds,
        int authCacheTtlSeconds,
        int recallWindowSeconds,
        int rateLimitPer10s,
        List<String> allowedOrigins) {

    public ChatProperties {
        if (presenceTtlMs <= 0) {
            presenceTtlMs = 60_000L;
        }
        if (historyLimit <= 0) {
            historyLimit = 1000;
        }
        if (dmHistoryLimit <= 0) {
            dmHistoryLimit = 500;
        }
        if (offlineLimit <= 0) {
            offlineLimit = 200;
        }
        if (maxContentLength <= 0) {
            maxContentLength = 2000;
        }
        if (maxNicknameLength <= 0) {
            maxNicknameLength = 32;
        }
        if (userServiceUrl == null || userServiceUrl.isBlank()) {
            userServiceUrl = "http://127.0.0.1:8101";
        }
        if (wsTicketTtlSeconds <= 0) {
            wsTicketTtlSeconds = 60;
        }
        if (authCacheTtlSeconds <= 0) {
            authCacheTtlSeconds = 60;
        }
        if (recallWindowSeconds <= 0) {
            recallWindowSeconds = 120;
        }
        if (rateLimitPer10s <= 0) {
            rateLimitPer10s = 20;
        }
        allowedOrigins = allowedOrigins == null || allowedOrigins.isEmpty()
                ? List.of("*")
                : List.copyOf(allowedOrigins);
    }
}
