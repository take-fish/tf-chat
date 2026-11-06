package com.fishmatrix.chat.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fishmatrix.chat.config.ChatAgentProperties;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AgentProfileCacheTest {

    private static final class StubClient implements AgentApiClient {

        final AtomicReference<String> name = new AtomicReference<>();
        int calls;

        @Override
        public Optional<String> displayName() {
            calls++;
            return Optional.ofNullable(name.get());
        }

        @Override
        public void stream(AgentCaller caller, String prompt, String sessionId,
                AgentStreamListener listener) {
            throw new UnsupportedOperationException("展示名缓存不发起对话");
        }
    }

    private static ChatAgentProperties properties(String fallback, int cacheSeconds) {
        return new ChatAgentProperties(
                true, "http://127.0.0.1:1", "prod", fallback, cacheSeconds,
                1, 5, 1, 4, 1, 4000, 5, 4000, 2000, 120, 16);
    }

    @Test
    @DisplayName("agent 还没回名字时用配置兜底，不让触发词直接消失")
    void fallsBackToConfiguredName() {
        StubClient client = new StubClient();
        AgentProfileCache cache = new AgentProfileCache(properties("小鱼", 60), client);

        assertThat(cache.mentionName()).isEqualTo("小鱼");
        cache.shutdown();
    }

    @Test
    @DisplayName("拿到 agent 配置的名字后优先用它")
    void prefersRemoteName() {
        StubClient client = new StubClient();
        client.name.set("FishMatrix Agent");
        AgentProfileCache cache = new AgentProfileCache(properties("小鱼", 60), client);

        cache.refreshNow();

        assertThat(cache.mentionName()).isEqualTo("FishMatrix Agent");
        assertThat(cache.refreshedAt()).isPositive();
        cache.shutdown();
    }

    @Test
    @DisplayName("刷新失败时保留上一次的名字")
    void keepsPreviousNameWhenRefreshFails() {
        StubClient client = new StubClient();
        client.name.set("小鱼");
        AgentProfileCache cache = new AgentProfileCache(properties("", 60), client);
        cache.refreshNow();

        client.name.set(null);
        cache.refreshNow();

        assertThat(cache.mentionName()).isEqualTo("小鱼");
        cache.shutdown();
    }

    @Test
    @DisplayName("超过缓存时间会异步刷新，读到新名字")
    void refreshesAsynchronouslyAfterTtl() throws Exception {
        StubClient client = new StubClient();
        client.name.set("小鱼");
        AgentProfileCache cache = new AgentProfileCache(properties("", 1), client);
        cache.refreshNow();
        assertThat(cache.mentionName()).isEqualTo("小鱼");

        client.name.set("新小鱼");
        Thread.sleep(1100);

        String current = cache.mentionName();
        // 触发刷新是异步的：先返回旧值，随后自动换成新名字
        long deadline = System.currentTimeMillis() + 3000;
        while (!"新小鱼".equals(current) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
            current = cache.mentionName();
        }
        assertThat(current).isEqualTo("新小鱼");
        cache.shutdown();
    }
}
