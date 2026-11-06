package com.fishmatrix.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 聊天接入智能助手（tf-agent）：@ 展示名触发，回复按片段实时广播。
 *
 * <p>展示名不写死在这里：运行时从 agent 的「试衣间」配置读取，管理员改名后
 * 最长 {@code profileCacheSeconds} 秒内生效。{@code fallbackName} 只在 agent
 * 暂时取不到名字时兜底，避免改名把功能整个弄挂。</p>
 */
@ConfigurationProperties(prefix = "app.chat.agent")
public record ChatAgentProperties(
        boolean enabled,
        String baseUrl,
        String env,
        String fallbackName,
        int profileCacheSeconds,
        int connectTimeoutSeconds,
        int turnTimeoutSeconds,
        int maxConcurrentTurns,
        int queueCapacity,
        int chunkMinChars,
        int chunkMaxChars,
        int chunkIntervalMs,
        int maxReplyChars,
        int maxPromptChars,
        int imageTtlSeconds,
        int imageCacheCapacity) {

    public ChatAgentProperties {
        baseUrl = baseUrl == null || baseUrl.isBlank() ? "http://127.0.0.1:8201" : baseUrl.strip();
        // 允许 CHAT_AGENT_URL 末尾带 /，拼接口时不能出现 http://host//api
        while (baseUrl.endsWith("/") && !baseUrl.endsWith("://")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        env = "test".equalsIgnoreCase(env == null ? "" : env.strip()) ? "test" : "prod";
        fallbackName = fallbackName == null ? "" : fallbackName.strip();
        if (profileCacheSeconds <= 0) {
            profileCacheSeconds = 60;
        }
        if (connectTimeoutSeconds <= 0) {
            connectTimeoutSeconds = 3;
        }
        if (turnTimeoutSeconds <= 0) {
            turnTimeoutSeconds = 120;
        }
        if (maxConcurrentTurns <= 0) {
            maxConcurrentTurns = 2;
        }
        if (queueCapacity <= 0) {
            queueCapacity = 16;
        }
        if (chunkMinChars <= 0) {
            chunkMinChars = 40;
        }
        if (chunkMaxChars <= 0) {
            chunkMaxChars = 400;
        }
        if (chunkMaxChars < chunkMinChars) {
            chunkMaxChars = chunkMinChars;
        }
        if (chunkIntervalMs <= 0) {
            chunkIntervalMs = 800;
        }
        if (maxReplyChars <= 0) {
            maxReplyChars = 4000;
        }
        if (maxPromptChars <= 0) {
            maxPromptChars = 2000;
        }
        if (imageTtlSeconds <= 0) {
            imageTtlSeconds = 120;
        }
        if (imageCacheCapacity <= 0) {
            imageCacheCapacity = 16;
        }
    }

    /** 触发词是否可用：开关打开且至少有一个名字（配置兜底或 agent 下发）。 */
    public boolean usable(String mentionName) {
        return enabled && mentionName != null && !mentionName.isBlank();
    }
}
