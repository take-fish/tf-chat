package com.fishmatrix.chat.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
@Slf4j
public class RedisSmokeStore implements SmokeStore {

    private static final String KEY = "tf:chat:smoke:sessions";
    private static final Duration TTL = Duration.ofHours(24);
    private static final TypeReference<List<SmokeSession>> TYPE = new TypeReference<>() { };

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    @Override
    public List<SmokeSession> load() {
        String payload = redis.opsForValue().get(KEY);
        if (payload == null || payload.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return new ArrayList<>(objectMapper.readValue(payload, TYPE));
        } catch (JsonProcessingException exception) {
            // 单条坏数据不应让首页整块不可用；下一次写入会覆盖。
            log.warn("冒烟测试状态反序列化失败，已忽略: {}", exception.getMessage());
            return new ArrayList<>();
        }
    }

    @Override
    public void save(List<SmokeSession> sessions) {
        if (sessions == null || sessions.isEmpty()) {
            redis.delete(KEY);
            return;
        }
        try {
            redis.opsForValue().set(KEY, objectMapper.writeValueAsString(sessions), TTL);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("冒烟测试状态序列化失败", exception);
        }
    }
}
