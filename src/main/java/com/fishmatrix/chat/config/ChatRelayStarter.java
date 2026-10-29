package com.fishmatrix.chat.config;

import com.fishmatrix.chat.pubsub.ChatRedisRelay;
import com.fishmatrix.chat.store.ChatStore;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

/**
 * Redis 不可用时不能阻塞应用启动：应用就绪后再启动 Pub/Sub 监听，失败则定时重试。
 * 容器不注册为 Bean，避免 Spring 生命周期在上下文刷新阶段强制启动。
 */
@Component
@ConditionalOnProperty(name = "app.chat.relay-enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class ChatRelayStarter {

    private final RedisMessageListenerContainer container = new RedisMessageListenerContainer();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "chat-relay-starter");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean initialized;

    public ChatRelayStarter(RedisConnectionFactory connectionFactory, ChatRedisRelay relay) {
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(relay, new ChannelTopic(ChatStore.EVENTS_CHANNEL));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void scheduleStart() {
        scheduler.scheduleWithFixedDelay(this::ensureStarted, 0, 15, TimeUnit.SECONDS);
    }

    private void ensureStarted() {
        if (container.isRunning()) {
            return;
        }
        try {
            if (!initialized) {
                // 非 Spring 管理生命周期，需手动完成 InitializingBean 初始化
                container.afterPropertiesSet();
                initialized = true;
            }
            container.start();
            log.info("聊天 Redis relay 已启动");
        } catch (Exception e) {
            log.warn("聊天 Redis relay 暂不可用，15 秒后重试: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
        try {
            if (container.isRunning()) {
                container.stop();
            }
        } catch (Exception e) {
            log.debug("停止聊天 relay 失败: {}", e.getMessage());
        }
    }
}
