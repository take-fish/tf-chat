package com.fishmatrix.chat.agent;

import com.fishmatrix.chat.config.ChatAgentProperties;
import jakarta.annotation.PreDestroy;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 缓存 agent 的展示名，作为「@名字」的触发词。
 *
 * <p>名字由管理员在 agent 侧配置，聊天不能写死；但也不能每条消息都去问一次。
 * 这里按 {@code profileCacheSeconds} 过期，过期后**异步**刷新：发消息的线程
 * 永远只用缓存值或兜底值，不会因为 agent 慢而卡住。取不到新名字时保留旧值，
 * 避免一次网络抖动把触发词弄没。</p>
 */
@Component
@Slf4j
public class AgentProfileCache {

    private final ChatAgentProperties properties;
    private final AgentApiClient client;
    private final AtomicReference<String> cached = new AtomicReference<>();
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private final ExecutorService refreshExecutor =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "chat-agent-profile");
                thread.setDaemon(true);
                return thread;
            });
    private volatile long refreshedAt;

    public AgentProfileCache(ChatAgentProperties properties, AgentApiClient client) {
        this.properties = properties;
        this.client = client;
    }

    /** 启动时先取一次，避免第一条 @ 落在“还不知道叫什么”的空窗期。 */
    @EventListener(ApplicationReadyEvent.class)
    public void warmup() {
        if (properties.enabled()) {
            triggerRefresh();
        }
    }

    @PreDestroy
    public void shutdown() {
        refreshExecutor.shutdownNow();
    }

    /** 当前可用的触发名：缓存 → 配置兜底 → 空（此时不触发）。 */
    public String mentionName() {
        String name = cached.get();
        if (expired()) {
            triggerRefresh();
        }
        if (name != null && !name.isBlank()) {
            return name;
        }
        return properties.fallbackName();
    }

    /** 测试与运维排查用：最近一次刷新时间。 */
    public long refreshedAt() {
        return refreshedAt;
    }

    /** 立即刷新一次（同步，可能阻塞到连接超时），只给测试和启动预热用。 */
    Optional<String> refreshNow() {
        Optional<String> name = client.displayName();
        name.filter(value -> !value.isBlank()).ifPresent(value -> {
            cached.set(value);
            refreshedAt = System.currentTimeMillis();
        });
        return name;
    }

    private boolean expired() {
        return System.currentTimeMillis() - refreshedAt
                >= properties.profileCacheSeconds() * 1000L;
    }

    private void triggerRefresh() {
        if (!refreshing.compareAndSet(false, true)) {
            return;
        }
        try {
            refreshExecutor.execute(() -> {
                try {
                    refreshNow();
                } catch (Exception exception) {
                    log.debug("刷新 agent 展示名失败: {}", exception.getMessage());
                } finally {
                    refreshing.set(false);
                }
            });
        } catch (Exception exception) {
            refreshing.set(false);
            log.debug("提交刷新 agent 展示名任务失败: {}", exception.getMessage());
        }
    }
}
