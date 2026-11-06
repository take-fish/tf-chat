package com.fishmatrix.chat.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fishmatrix.chat.config.ChatAgentProperties;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class AgentImageCacheTest {

    private final AtomicLong now = new AtomicLong(1_000_000L);

    @Test
    void remembersGroupImagePerConversationAndSender() {
        AgentImageCache cache = cache(120, 16);

        cache.rememberGroup("group:dev", "u1", "data:image/png;base64,AQID");

        assertThat(cache.get("group:dev", "u1")).hasValueSatisfying(ref -> {
            assertThat(ref.kind()).isEqualTo(AgentImageCache.Kind.GROUP_DATA_URL);
            assertThat(ref.value()).isEqualTo("data:image/png;base64,AQID");
        });
        assertThat(cache.get("group:dev", "u2")).isEmpty();
        assertThat(cache.get("group:other", "u1")).isEmpty();
        assertThat(cache.get("dm:u1:u2", "u1")).isEmpty();
    }

    @Test
    void remembersDirectImageIdSeparately() {
        AgentImageCache cache = cache(120, 16);

        cache.rememberDirect("dm:u1:u2", "u1", "img-1");

        assertThat(cache.get("dm:u1:u2", "u1")).hasValueSatisfying(ref -> {
            assertThat(ref.kind()).isEqualTo(AgentImageCache.Kind.DIRECT_IMAGE_ID);
            assertThat(ref.value()).isEqualTo("img-1");
        });
    }

    @Test
    void expiresEntriesAfterTtl() {
        AgentImageCache cache = cache(120, 16);
        cache.rememberGroup("group:dev", "u1", "data:image/png;base64,AQID");

        now.addAndGet(119_000L);
        assertThat(cache.get("group:dev", "u1")).isPresent();

        now.addAndGet(1_000L);
        assertThat(cache.get("group:dev", "u1")).isEmpty();
        assertThat(cache.size()).isZero();
    }

    @Test
    void evictsLeastRecentlyUsedWhenFull() {
        AgentImageCache cache = cache(120, 2);
        cache.rememberGroup("group:a", "u1", "a");
        cache.rememberGroup("group:b", "u1", "b");

        assertThat(cache.get("group:a", "u1")).isPresent();
        cache.rememberGroup("group:c", "u1", "c");

        assertThat(cache.get("group:a", "u1")).isPresent();
        assertThat(cache.get("group:b", "u1")).isEmpty();
        assertThat(cache.get("group:c", "u1")).isPresent();
    }

    @Test
    void laterImageReplacesEarlierOneInSameConversation() {
        AgentImageCache cache = cache(120, 16);
        cache.rememberGroup("group:dev", "u1", "old");
        cache.rememberGroup("group:dev", "u1", "new");

        assertThat(cache.get("group:dev", "u1"))
                .hasValueSatisfying(ref -> assertThat(ref.value()).isEqualTo("new"));
        assertThat(cache.size()).isEqualTo(1);
    }

    private AgentImageCache cache(int ttlSeconds, int capacity) {
        return new AgentImageCache(properties(ttlSeconds, capacity), now::get);
    }

    private static ChatAgentProperties properties(int ttlSeconds, int capacity) {
        return new ChatAgentProperties(
                true, "http://127.0.0.1:1", "test", "小鱼",
                60, 3, 120, 2, 16, 40, 400, 800, 4000, 2000, ttlSeconds, capacity);
    }
}
