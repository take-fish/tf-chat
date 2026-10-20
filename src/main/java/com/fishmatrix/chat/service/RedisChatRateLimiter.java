package com.fishmatrix.chat.service;

import com.fishmatrix.chat.config.ChatProperties;
import com.fishmatrix.chat.model.ChatException;
import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class RedisChatRateLimiter implements ChatRateLimiter {

    private static final long WINDOW_MS = 10_000L;

    private final StringRedisTemplate redis;
    private final ChatProperties properties;

    public RedisChatRateLimiter(StringRedisTemplate redis, ChatProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    @Override
    public void acquire(String userId) {
        long window = System.currentTimeMillis() / WINDOW_MS;
        String key = "tf:chat:rate:" + userId + ":" + window;
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, Duration.ofSeconds(12));
        }
        if (count != null && count > properties.rateLimitPer10s()) {
            throw ChatException.rateLimited("发送过于频繁，请稍后再试");
        }
    }
}
