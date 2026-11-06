package com.fishmatrix.chat.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishmatrix.chat.config.ChatAgentProperties;
import com.fishmatrix.common.auth.IdentityHeaders;
import com.fishmatrix.common.auth.IdentitySigner;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class HttpAgentApiClientTest {

    private static final String SECRET = "test-secret";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private final AtomicReference<Map<String, List<String>>> chatHeaders = new AtomicReference<>();
    private final AtomicReference<String> chatBody = new AtomicReference<>();
    private final IdentitySigner signer = new IdentitySigner(SECRET, 0);

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/agent/chat-identity", exchange -> respond(
                exchange,
                "{\"code\":0,\"data\":{\"name\":\"小鱼\",\"mention\":\"@小鱼\"}}"));
        server.createContext("/api/v1/agent/chat", exchange -> {
            Map<String, List<String>> snapshot = new LinkedHashMap<>();
            exchange.getRequestHeaders().forEach(
                    (name, values) -> snapshot.put(name, List.copyOf(values)));
            chatHeaders.set(snapshot);
            chatBody.set(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, """
                    data: {"type":"start"}

                    data: {"type":"delta","text":"你好"}

                    data: {"type":"delta","text":"，世界"}

                    data: {"type":"done","text":"你好，世界"}

                    """);
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    @DisplayName("展示名从 agent 读，流式回答按 delta 回调")
    void streamsEventsFromAgent() {
        HttpAgentApiClient client = client();

        assertThat(client.displayName()).contains("小鱼");

        List<String> deltas = new ArrayList<>();
        AtomicReference<String> completed = new AtomicReference<>();
        client.stream(
                caller(),
                "最近有 5xx 吗",
                "chat:group:dev-group",
                new AgentStreamListener() {
                    @Override
                    public void onDelta(String text) {
                        deltas.add(text);
                    }

                    @Override
                    public void onComplete(String fullText) {
                        completed.set(fullText);
                    }

                    @Override
                    public void onError(String message) {
                        throw new AssertionError("不应该报错：" + message);
                    }
                });

        assertThat(deltas).containsExactly("你好", "，世界");
        assertThat(completed.get()).isEqualTo("你好，世界");
    }

    @Test
    @DisplayName("调用 agent 时带的是触发者本人的签名身份，不是服务账号")
    void signsCallerIdentity() {
        HttpAgentApiClient client = client();
        client.stream(caller(), "你好", "session-1", new NoopListener());

        Map<String, List<String>> headers = chatHeaders.get();
        assertThat(headers).isNotNull();
        assertThat(header(headers, IdentityHeaders.USER_ID)).isEqualTo("42");
        assertThat(header(headers, IdentityHeaders.PERMISSIONS)).isEqualTo("agent.chat.use");

        String canonical = signer.canonical(
                42,
                decode(header(headers, IdentityHeaders.USERNAME)),
                decode(header(headers, IdentityHeaders.NICKNAME)),
                decode(header(headers, IdentityHeaders.ROLE)),
                List.of("USER"),
                List.of("agent.chat.use"),
                Long.parseLong(header(headers, IdentityHeaders.EXPIRES_AT)));
        assertThat(signer.sign(canonical)).isEqualTo(header(headers, IdentityHeaders.SIGNATURE));
    }

    @Test
    @DisplayName("调用 agent 固定走 HTTP/1.1，不发 h2c Upgrade（uvicorn 会丢 POST body 返回 422）")
    void doesNotSendH2cUpgrade() {
        client().stream(caller(), "你好", "session-1", new NoopListener());

        Map<String, List<String>> headers = chatHeaders.get();
        assertThat(headers).isNotNull();
        assertThat(header(headers, "Upgrade")).isNull();
        assertThat(header(headers, "Connection")).isNullOrEmpty();
    }

    @Test
    @DisplayName("agent 返回 403 时给一句能看懂的权限提示")
    void mapsForbiddenToFriendlyMessage() {
        server.removeContext("/api/v1/agent/chat");
        server.createContext("/api/v1/agent/chat", exchange -> {
            exchange.sendResponseHeaders(403, -1);
            exchange.close();
        });

        List<String> errors = new ArrayList<>();
        client().stream(caller(), "你好", "s", new NoopListener() {
            @Override
            public void onError(String message) {
                errors.add(message);
            }
        });

        assertThat(errors).containsExactly("没有使用智能助手的权限（需要 agent.chat.use）");
    }

    @Test
    @DisplayName("有图时请求体带 image.mime/data，无图时没有 image 字段")
    void includesImageOnlyWhenPresent() throws Exception {
        HttpAgentApiClient client = client();
        client.stream(caller(), "图里是什么", "session-1", new NoopListener());

        assertThat(MAPPER.readTree(chatBody.get()).has("image")).isFalse();

        byte[] bytes = {1, 2, 3};
        String base64 = Base64.getEncoder().encodeToString(bytes);
        client.stream(
                caller(),
                "图里是什么",
                "session-1",
                new AgentImage("image/png", base64, bytes.length),
                new NoopListener());

        var body = MAPPER.readTree(chatBody.get());
        assertThat(body.path("message").asText()).isEqualTo("图里是什么");
        assertThat(body.path("image").path("mime").asText()).isEqualTo("image/png");
        assertThat(body.path("image").path("data").asText()).isEqualTo(base64);
    }

    @Test
    @DisplayName("CHAT_AGENT_URL 末尾带 / 也能拼出正确的接口地址")
    void trimsTrailingSlash() {
        ChatAgentProperties properties = new ChatAgentProperties(
                true, "http://127.0.0.1:8201/", "test", "小鱼",
                60, 3, 120, 2, 16, 40, 400, 800, 4000, 2000, 120, 16);

        assertThat(properties.baseUrl()).isEqualTo("http://127.0.0.1:8201");
        assertThat(properties.usable("小鱼")).isTrue();
        assertThat(properties.usable(" ")).isFalse();
    }

    private HttpAgentApiClient client() {
        ChatAgentProperties properties = new ChatAgentProperties(
                true,
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "test",
                "",
                60, 1, 5, 1, 4, 40, 400, 800, 4000, 2000, 120, 16);
        @SuppressWarnings("unchecked")
        ObjectProvider<IdentitySigner> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(signer);
        return new HttpAgentApiClient(properties, provider);
    }

    private static AgentCaller caller() {
        return new AgentCaller(42, "alice", "甲", "USER", List.of("USER"), List.of("agent.chat.use"));
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static String header(Map<String, List<String>> headers, String name) {
        return headers.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(entry -> entry.getValue().get(0))
                .findFirst()
                .orElse(null);
    }

    private static String decode(String value) {
        return new String(
                Base64.getUrlDecoder().decode(value + "=".repeat((4 - value.length() % 4) % 4)),
                StandardCharsets.UTF_8);
    }

    private static class NoopListener implements AgentStreamListener {

        @Override
        public void onDelta(String text) {
        }

        @Override
        public void onComplete(String fullText) {
        }

        @Override
        public void onError(String message) {
            throw new AssertionError("不应该报错：" + message);
        }
    }
}
