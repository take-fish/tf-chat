package com.fishmatrix.chat.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishmatrix.chat.config.ChatProperties;
import com.fishmatrix.chat.model.ChatException;
import com.fishmatrix.chat.model.ChatIdentity;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ChatAuthServiceTest {

    private static final String ME_PATH = "http://user/api/v1/user/auth/me";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private MockRestServiceServer server;
    private ChatAuthService authService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);

        RestClient.Builder builder = RestClient.builder().baseUrl("http://user");
        server = MockRestServiceServer.bindTo(builder).build();
        authService = new ChatAuthService(
                builder.build(), redis, objectMapper, properties());
    }

    @Test
    void requiresBearerToken() {
        assertThatThrownBy(() -> authService.require(null))
                .isInstanceOf(ChatException.class)
                .hasMessageContaining("请先登录");
        assertThatThrownBy(() -> authService.require("Basic abc"))
                .isInstanceOf(ChatException.class)
                .hasMessageContaining("请先登录");
        assertThatThrownBy(() -> authService.require("Bearer   "))
                .isInstanceOf(ChatException.class)
                .hasMessageContaining("请先登录");
    }

    @Test
    void verifiesWithUserServiceAndCachesIdentity() throws Exception {
        server.expect(requestTo(ME_PATH))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer token-1"))
                .andRespond(withSuccess(
                        """
                        {"id":42,"username":"alice","nickname":"甲","role":"ADMIN","status":"ACTIVE"}
                        """,
                        MediaType.APPLICATION_JSON));

        ChatIdentity identity = authService.require("Bearer token-1");

        assertThat(identity.accountId()).isEqualTo(42);
        assertThat(identity.userId()).isEqualTo("u42");
        assertThat(identity.nickname()).isEqualTo("甲");
        assertThat(identity.role()).isEqualTo("ADMIN");
        verify(values).set(
                startsWith("tf:chat:auth:"),
                anyString(),
                eq(Duration.ofSeconds(60)));
        server.verify();
    }

    @Test
    void usesCachedIdentityWithoutCallingUserService() throws Exception {
        ChatIdentity cached = ChatIdentity.of(7, "乙", "USER");
        when(values.get(startsWith("tf:chat:auth:")))
                .thenReturn(objectMapper.writeValueAsString(cached));

        ChatIdentity identity = authService.require("Bearer token-2");

        assertThat(identity).isEqualTo(cached);
        server.verify();
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void mapsClientErrorsToUnauthorized() {
        server.expect(requestTo(ME_PATH))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> authService.require("Bearer expired"))
                .isInstanceOf(ChatException.class)
                .hasMessageContaining("登录已失效");
    }

    @Test
    void mapsServerErrorsToUnavailable() {
        server.expect(requestTo(ME_PATH))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> authService.require("Bearer token"))
                .isInstanceOf(ChatException.class)
                .hasMessageContaining("暂不可用");
    }

    @Test
    void rejectsInactiveAccounts() {
        server.expect(requestTo(ME_PATH))
                .andRespond(withSuccess(
                        """
                        {"id":42,"nickname":"甲","role":"USER","status":"DISABLED"}
                        """,
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> authService.require("Bearer token"))
                .isInstanceOf(ChatException.class)
                .hasMessageContaining("账号当前不可用");
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void parsesBearerTokenCaseInsensitively() {
        assertThat(ChatAuthService.bearerToken("bearer abc")).isEqualTo("abc");
        assertThat(ChatAuthService.bearerToken(" Bearer  abc ")).isEqualTo("abc");
        assertThat(ChatAuthService.bearerToken("Bearer")).isNull();
        assertThat(ChatAuthService.bearerToken("")).isNull();
    }

    private static ChatProperties properties() {
        return new ChatProperties(
                false, 60_000, 1000, 500, 200, 2000, 32,
                "http://user", 60, 60, 120, 20, List.of("*"));
    }
}
