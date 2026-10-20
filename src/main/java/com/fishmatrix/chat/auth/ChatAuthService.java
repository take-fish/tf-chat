package com.fishmatrix.chat.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishmatrix.chat.config.ChatProperties;
import com.fishmatrix.chat.model.ChatException;
import com.fishmatrix.chat.model.ChatIdentity;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 通过 tf-user 的 /auth/me 校验登录会话，并把结果短暂缓存到 Redis。
 * userId 统一为 u{accountId}，昵称与角色以用户服务为准，客户端无法自报。
 */
@Service
public class ChatAuthService {

    private static final Logger log = LoggerFactory.getLogger(ChatAuthService.class);
    private static final String CACHE_PREFIX = "tf:chat:auth:";

    private final RestClient userClient;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final ChatProperties properties;

    public ChatAuthService(
            RestClient chatUserRestClient,
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            ChatProperties properties) {
        this.userClient = chatUserRestClient;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public ChatIdentity require(String authorization) {
        String token = bearerToken(authorization);
        if (token == null) {
            throw ChatException.unauthorized("请先登录");
        }
        String cacheKey = CACHE_PREFIX + sha256(token);
        ChatIdentity cached = readCache(cacheKey);
        if (cached != null) {
            return cached;
        }

        UserMe user;
        try {
            user = userClient.get()
                    .uri("/api/v1/user/auth/me")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .retrieve()
                    .body(UserMe.class);
        } catch (HttpClientErrorException exception) {
            throw ChatException.unauthorized("登录已失效，请重新登录");
        } catch (RestClientException exception) {
            log.warn("用户服务校验失败: {}", exception.getMessage());
            throw ChatException.unavailable("登录服务暂不可用");
        }
        if (user == null || user.id() <= 0 || user.nickname() == null || user.nickname().isBlank()) {
            throw ChatException.unauthorized("登录已失效，请重新登录");
        }
        if (user.status() != null && !"ACTIVE".equalsIgnoreCase(user.status())) {
            throw ChatException.unauthorized("账号当前不可用");
        }
        ChatIdentity identity = ChatIdentity.of(user.id(), user.nickname(), user.role());
        writeCache(cacheKey, identity);
        return identity;
    }

    public static String bearerToken(String authorization) {
        if (authorization == null) {
            return null;
        }
        String value = authorization.trim();
        if (!value.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String token = value.substring(7).trim();
        return token.isEmpty() ? null : token;
    }

    private ChatIdentity readCache(String key) {
        try {
            String payload = redis.opsForValue().get(key);
            return payload == null ? null : objectMapper.readValue(payload, ChatIdentity.class);
        } catch (DataAccessException | JsonProcessingException exception) {
            log.debug("读取聊天鉴权缓存失败: {}", exception.getMessage());
            return null;
        }
    }

    private void writeCache(String key, ChatIdentity identity) {
        try {
            redis.opsForValue().set(
                    key,
                    objectMapper.writeValueAsString(identity),
                    Duration.ofSeconds(properties.authCacheTtlSeconds()));
        } catch (DataAccessException | JsonProcessingException exception) {
            log.debug("写入聊天鉴权缓存失败: {}", exception.getMessage());
        }
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    record UserMe(long id, String username, String nickname, String role, String status) {
    }
}
