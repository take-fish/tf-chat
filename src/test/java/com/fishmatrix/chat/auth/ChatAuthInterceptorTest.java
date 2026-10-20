package com.fishmatrix.chat.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishmatrix.chat.model.ChatException;
import com.fishmatrix.chat.model.ChatIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ChatAuthInterceptorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ChatAuthService authService;
    private ChatAuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        authService = mock(ChatAuthService.class);
        interceptor = new ChatAuthInterceptor(authService, objectMapper);
    }

    @Test
    void storesIdentityOnSuccess() throws Exception {
        ChatIdentity identity = ChatIdentity.of(3, "甲", "USER");
        when(authService.require("Bearer token")).thenReturn(identity);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertThat(allowed).isTrue();
        assertThat(request.getAttribute(ChatAuthInterceptor.ATTR_IDENTITY)).isEqualTo(identity);
    }

    @Test
    void writesUnauthorizedJsonAndStopsChain() throws Exception {
        when(authService.require("Bearer bad"))
                .thenThrow(ChatException.unauthorized("登录已失效，请重新登录"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer bad");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.get("error").asText()).isEqualTo("UNAUTHORIZED");
        assertThat(body.get("message").asText()).contains("登录已失效");
    }

    @Test
    void mapsBackendOutageToServiceUnavailable() throws Exception {
        when(authService.require("Bearer token"))
                .thenThrow(ChatException.unavailable("登录服务暂不可用"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(objectMapper.readTree(response.getContentAsString())
                .get("error").asText()).isEqualTo("CHAT_UNAVAILABLE");
    }
}
