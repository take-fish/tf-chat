package com.fishmatrix.chat.agent;

import com.fishmatrix.chat.config.ChatAgentProperties;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 记住「某人在某个会话里刚发的一张图」，供紧接着的 @ 提问使用。
 *
 * <p>为什么需要缓存：聊天协议里图片消息和 @ 文本是两条独立消息。这里按
 * {@code conversationId + 发送者 userId} 隔离，只有本人刚发的图会挂到本人的提问上；
 * TTL 默认 120 秒，超时或容量满了自动丢弃。</p>
 *
 * <p>群聊只存 data URL，私聊只存 imageId（真正读取走 {@link
 * com.fishmatrix.chat.service.ChatImageService#load}，保留原有权限校验）。
 * 图片本身不在这里解密、不落盘。</p>
 */
@Component
public class AgentImageCache {

    public enum Kind {
        GROUP_DATA_URL,
        DIRECT_IMAGE_ID
    }

    public record ImageRef(Kind kind, String value) {
    }

    private record Entry(ImageRef ref, long expiresAt) {
    }

    private final long ttlMs;
    private final int capacity;
    private final LongSupplier clock;
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);

    @Autowired
    public AgentImageCache(ChatAgentProperties properties) {
        this(properties, System::currentTimeMillis);
    }

    AgentImageCache(ChatAgentProperties properties, LongSupplier clock) {
        this.ttlMs = Math.max(1, properties.imageTtlSeconds()) * 1000L;
        this.capacity = Math.max(1, properties.imageCacheCapacity());
        this.clock = clock;
    }

    public synchronized void rememberGroup(String conversationId, String userId, String dataUrl) {
        remember(conversationId, userId, new ImageRef(Kind.GROUP_DATA_URL, dataUrl));
    }

    public synchronized void rememberDirect(String conversationId, String userId, String imageId) {
        remember(conversationId, userId, new ImageRef(Kind.DIRECT_IMAGE_ID, imageId));
    }

    public synchronized Optional<ImageRef> get(String conversationId, String userId) {
        long now = clock.getAsLong();
        prune(now);
        Entry entry = entries.get(key(conversationId, userId));
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.expiresAt() <= now) {
            entries.remove(key(conversationId, userId));
            return Optional.empty();
        }
        return Optional.of(entry.ref());
    }

    /** 测试用：当前缓存条数。 */
    synchronized int size() {
        prune(clock.getAsLong());
        return entries.size();
    }

    /** 测试用：清空缓存，避免用例之间串数据。 */
    public synchronized void clear() {
        entries.clear();
    }

    private void remember(String conversationId, String userId, ImageRef ref) {
        long now = clock.getAsLong();
        prune(now);
        String key = key(conversationId, userId);
        entries.put(key, new Entry(ref, now + ttlMs));
        while (entries.size() > capacity) {
            Iterator<Map.Entry<String, Entry>> iterator = entries.entrySet().iterator();
            if (!iterator.hasNext()) {
                break;
            }
            iterator.next();
            iterator.remove();
        }
    }

    private void prune(long now) {
        entries.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
    }

    private static String key(String conversationId, String userId) {
        return conversationId + "|" + userId;
    }
}
