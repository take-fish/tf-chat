package com.fishmatrix.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.chat")
public record ChatProperties(
        boolean relayEnabled,
        long presenceTtlMs,
        int historyLimit,
        int dmHistoryLimit,
        int offlineLimit,
        int maxContentLength,
        int maxNicknameLength) {

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
    }
}
