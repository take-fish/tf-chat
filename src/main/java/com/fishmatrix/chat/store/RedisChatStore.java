package com.fishmatrix.chat.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishmatrix.chat.config.ChatProperties;
import com.fishmatrix.chat.model.ChatEvent;
import com.fishmatrix.chat.model.ChatMessage;
import com.fishmatrix.chat.model.OnlineMember;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Repository;

@Repository
public class RedisChatStore implements ChatStore {

    private static final String OFFLINE_PREFIX = "tf:chat:offline:";
    private static final String PRESENCE_PREFIX = "tf:chat:presence:";
    private static final String PRESENCE_META_PREFIX = "tf:chat:presence:meta:";
    private static final String ONLINE_KEY = "tf:chat:online";
    private static final String ONLINE_META_KEY = "tf:chat:online:meta";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final ChatProperties properties;

    public RedisChatStore(
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            ChatProperties properties) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public void addOfflineMessage(String userId, ChatMessage message) {
        String key = OFFLINE_PREFIX + userId;
        StreamOperations<String, String, String> stream = redis.opsForStream();
        MapRecord<String, String, String> record =
                StreamRecords.string(Map.of("payload", write(message))).withStreamKey(key);
        stream.add(record);
        stream.trim(key, properties.offlineLimit(), true);
    }

    @Override
    public List<ChatMessage> drainOfflineMessages(String userId) {
        String key = OFFLINE_PREFIX + userId;
        if (!Boolean.TRUE.equals(redis.hasKey(key))) {
            return List.of();
        }
        // 先原子改名再读取，避免读取与删除之间新到的消息被误删
        String temporary = key + ":draining:" + UUID.randomUUID();
        try {
            redis.rename(key, temporary);
        } catch (Exception exception) {
            return List.of();
        }
        try {
            return readStream(temporary, properties.offlineLimit());
        } finally {
            redis.delete(temporary);
        }
    }

    @Override
    public void markOnline(OnlineMember member, long ttlMs) {
        long expireAt = System.currentTimeMillis() + ttlMs;
        redis.opsForZSet().add(ONLINE_KEY, member.userId(), expireAt);
        redis.opsForHash().put(ONLINE_META_KEY, member.userId(), write(member));
    }

    @Override
    public boolean isOnline(String userId) {
        Double score = redis.opsForZSet().score(ONLINE_KEY, userId);
        return score != null && score > System.currentTimeMillis();
    }

    @Override
    public void markOffline(String userId) {
        redis.opsForZSet().remove(ONLINE_KEY, userId);
        redis.opsForHash().delete(ONLINE_META_KEY, userId);
    }

    @Override
    public void upsertPresence(OnlineMember member, long ttlMs) {
        String zsetKey = PRESENCE_PREFIX + member.groupId();
        String metaKey = PRESENCE_META_PREFIX + member.groupId();
        long expireAt = System.currentTimeMillis() + ttlMs;
        redis.opsForZSet().add(zsetKey, member.userId(), expireAt);
        redis.opsForHash().put(metaKey, member.userId(), write(member));
    }

    @Override
    public void removePresence(String groupId, String userId) {
        redis.opsForZSet().remove(PRESENCE_PREFIX + groupId, userId);
        redis.opsForHash().delete(PRESENCE_META_PREFIX + groupId, userId);
    }

    @Override
    public List<OnlineMember> members(String groupId) {
        String zsetKey = PRESENCE_PREFIX + groupId;
        String metaKey = PRESENCE_META_PREFIX + groupId;
        List<OnlineMember> members = readMembers(zsetKey, metaKey);
        return members.stream()
                .filter(member -> groupId.equals(member.groupId()))
                .toList();
    }

    @Override
    public List<OnlineMember> onlineMembers() {
        return readMembers(ONLINE_KEY, ONLINE_META_KEY);
    }

    @Override
    public void publish(ChatEvent event) {
        redis.convertAndSend(EVENTS_CHANNEL, write(event));
    }

    private List<OnlineMember> readMembers(String zsetKey, String metaKey) {
        long now = System.currentTimeMillis();
        ZSetOperations<String, String> zset = redis.opsForZSet();
        HashOperations<String, String, String> hash = redis.opsForHash();

        Set<String> expired = zset.rangeByScore(zsetKey, 0, now);
        if (expired != null && !expired.isEmpty()) {
            zset.remove(zsetKey, expired.toArray());
            hash.delete(metaKey, expired.toArray());
        }

        Set<String> userIds = zset.rangeByScore(zsetKey, now, Double.MAX_VALUE);
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        List<String> payloads = hash.multiGet(metaKey, new ArrayList<>(userIds));
        List<OnlineMember> members = new ArrayList<>();
        for (String payload : payloads) {
            if (payload == null) {
                continue;
            }
            members.add(read(payload, OnlineMember.class));
        }
        return members;
    }

    private List<ChatMessage> readStream(String key, int limit) {
        StreamOperations<String, String, String> stream = redis.opsForStream();
        List<MapRecord<String, String, String>> records =
                stream.reverseRange(key, Range.unbounded(), Limit.limit().count(Math.max(1, limit)));
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        List<MapRecord<String, String, String>> ordered = new ArrayList<>(records);
        java.util.Collections.reverse(ordered);
        List<ChatMessage> messages = new ArrayList<>();
        for (MapRecord<String, String, String> record : ordered) {
            String payload = record.getValue().get("payload");
            if (payload != null) {
                messages.add(read(payload, ChatMessage.class));
            }
        }
        return messages;
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("消息序列化失败", e);
        }
    }

    private <T> T read(String payload, Class<T> type) {
        try {
            return objectMapper.readValue(payload, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("消息反序列化失败", e);
        }
    }
}
