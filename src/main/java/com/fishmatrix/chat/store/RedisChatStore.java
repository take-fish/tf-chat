package com.fishmatrix.chat.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishmatrix.chat.config.ChatProperties;
import com.fishmatrix.chat.model.ChatEvent;
import com.fishmatrix.chat.model.ChatGroup;
import com.fishmatrix.chat.model.ChatMessage;
import com.fishmatrix.chat.model.OnlineMember;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Repository;

@Repository
public class RedisChatStore implements ChatStore {

    private static final String GROUPS_KEY = "tf:chat:groups";
    private static final String GROUP_HISTORY_PREFIX = "tf:chat:history:";
    private static final String DM_HISTORY_PREFIX = "tf:chat:dm:";
    private static final String OFFLINE_PREFIX = "tf:chat:offline:";
    private static final String PRESENCE_PREFIX = "tf:chat:presence:";
    private static final String PRESENCE_META_PREFIX = "tf:chat:presence:meta:";
    private static final String ONLINE_KEY = "tf:chat:online";
    private static final String ONLINE_META_KEY = "tf:chat:online:meta";

    private static final List<ChatGroup> DEFAULT_GROUPS = List.of(
            new ChatGroup("dev-group", "研发协作", "日常研发、需求联调与代码评审"),
            new ChatGroup("ops-oncall", "运维值班", "告警、发布与线上故障协同"),
            new ChatGroup("tech-share", "技术分享", "架构方案与工具链交流"));

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
    public void ensureGroups() {
        HashOperations<String, String, String> hash = redis.opsForHash();
        for (ChatGroup group : DEFAULT_GROUPS) {
            hash.putIfAbsent(GROUPS_KEY, group.id(), write(group));
        }
    }

    @Override
    public List<ChatGroup> groups() {
        HashOperations<String, String, String> hash = redis.opsForHash();
        Map<String, String> entries = hash.entries(GROUPS_KEY);
        List<ChatGroup> groups = new ArrayList<>();
        for (String value : entries.values()) {
            groups.add(read(value, ChatGroup.class));
        }
        groups.sort(Comparator.comparingInt(group -> orderOf(group.id())));
        return groups;
    }

    @Override
    public boolean groupExists(String groupId) {
        return Boolean.TRUE.equals(redis.opsForHash().hasKey(GROUPS_KEY, groupId));
    }

    @Override
    public ChatMessage appendGroupMessage(String groupId, ChatMessage message) {
        appendToStream(GROUP_HISTORY_PREFIX + groupId, message, properties.historyLimit());
        return message;
    }

    @Override
    public List<ChatMessage> groupHistory(String groupId, int limit) {
        return readStream(GROUP_HISTORY_PREFIX + groupId, limit);
    }

    @Override
    public ChatMessage appendDirectMessage(ChatMessage message) {
        appendToStream(dmKey(message.fromUserId(), message.toUserId()), message, properties.dmHistoryLimit());
        return message;
    }

    @Override
    public List<ChatMessage> directHistory(String userA, String userB, int limit) {
        return readStream(dmKey(userA, userB), limit);
    }

    @Override
    public void addOfflineMessage(String userId, ChatMessage message) {
        appendToStream(OFFLINE_PREFIX + userId, message, properties.offlineLimit());
    }

    @Override
    public List<ChatMessage> drainOfflineMessages(String userId) {
        String key = OFFLINE_PREFIX + userId;
        List<ChatMessage> messages = readStream(key, properties.offlineLimit());
        redis.delete(key);
        return messages;
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
            OnlineMember member = read(payload, OnlineMember.class);
            if (groupId.equals(member.groupId())) {
                members.add(member);
            }
        }
        return members;
    }

    @Override
    public void publish(ChatEvent event) {
        redis.convertAndSend(EVENTS_CHANNEL, write(event));
    }

    private void appendToStream(String key, ChatMessage message, int maxLength) {
        StreamOperations<String, String, String> stream = redis.opsForStream();
        MapRecord<String, String, String> record =
                StreamRecords.string(Map.of("payload", write(message))).withStreamKey(key);
        stream.add(record);
        stream.trim(key, maxLength, true);
    }

    private List<ChatMessage> readStream(String key, int limit) {
        StreamOperations<String, String, String> stream = redis.opsForStream();
        List<MapRecord<String, String, String>> records = stream.range(key, Range.unbounded());
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        int from = Math.max(0, records.size() - Math.max(1, limit));
        List<ChatMessage> messages = new ArrayList<>();
        for (MapRecord<String, String, String> record : records.subList(from, records.size())) {
            String payload = record.getValue().get("payload");
            if (payload != null) {
                messages.add(read(payload, ChatMessage.class));
            }
        }
        return messages;
    }

    private String dmKey(String userA, String userB) {
        String first = userA.compareTo(userB) <= 0 ? userA : userB;
        String second = userA.compareTo(userB) <= 0 ? userB : userA;
        return DM_HISTORY_PREFIX + first + ":" + second;
    }

    private int orderOf(String groupId) {
        for (int i = 0; i < DEFAULT_GROUPS.size(); i++) {
            if (DEFAULT_GROUPS.get(i).id().equals(groupId)) {
                return i;
            }
        }
        return DEFAULT_GROUPS.size();
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
