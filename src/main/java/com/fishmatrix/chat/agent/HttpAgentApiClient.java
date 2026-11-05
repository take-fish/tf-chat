package com.fishmatrix.chat.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishmatrix.chat.config.ChatAgentProperties;
import com.fishmatrix.common.auth.IdentityHeaders;
import com.fishmatrix.common.auth.IdentitySigner;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 直连 tf-agent（不经网关）：聊天服务代表触发者签发 X-FM-* 身份头，
 * 权限、审计都按真实用户算。
 *
 * <p>SSE 用 {@link HttpResponse.BodyHandlers#ofLines()} 边读边分发，
 * 所以回答能一段段刷出来，而不是等整段生成完。</p>
 */
@Component
@Slf4j
public class HttpAgentApiClient implements AgentApiClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final String CHAT_PATH = "/api/v1/agent/chat";
    private static final String IDENTITY_PATH = "/api/v1/agent/chat-identity";
    private static final long HEADER_TTL_SECONDS = 120;

    private final ChatAgentProperties properties;
    private final ObjectProvider<IdentitySigner> signerProvider;
    private final HttpClient http;

    public HttpAgentApiClient(
            ChatAgentProperties properties, ObjectProvider<IdentitySigner> signerProvider) {
        this.properties = properties;
        this.signerProvider = signerProvider;
        // JDK HttpClient 默认 HTTP_2，对 http:// 会先发 h2c Upgrade；uvicorn 不支持且
        // 会丢掉带 body 的 POST，agent 侧表现为 422「请求参数校验未通过」。固定 HTTP/1.1。
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(properties.connectTimeoutSeconds()))
                .build();
    }

    @Override
    public Optional<String> displayName() {
        HttpRequest request = withIdentity(
                        HttpRequest.newBuilder(uri(IDENTITY_PATH))
                                .timeout(Duration.ofSeconds(properties.connectTimeoutSeconds()))
                                .header("Accept", "application/json"),
                        new AgentCaller(0L, "tf-chat", "聊天中继", "SERVICE", List.of(), List.of()))
                .GET()
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.debug("读取 agent 展示名失败: HTTP {}", response.statusCode());
                return Optional.empty();
            }
            String name = MAPPER.readTree(response.body())
                    .path("data").path("name").asText("").strip();
            return name.isEmpty() ? Optional.empty() : Optional.of(name);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception exception) {
            log.debug("读取 agent 展示名失败: {}", exception.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void stream(
            AgentCaller caller, String prompt, String sessionId, AgentStreamListener listener) {
        HttpRequest request = withIdentity(
                        HttpRequest.newBuilder(uri(CHAT_PATH))
                                .timeout(Duration.ofSeconds(properties.turnTimeoutSeconds()))
                                .header("Content-Type", "application/json; charset=utf-8")
                                .header("Accept", "text/event-stream"),
                        caller)
                .POST(HttpRequest.BodyPublishers.ofString(
                        body(prompt, sessionId), StandardCharsets.UTF_8))
                .build();

        long deadline = System.currentTimeMillis() + properties.turnTimeoutSeconds() * 1000L;
        HttpResponse<Stream<String>> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofLines());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            listener.onError("智能助手调用被中断");
            return;
        } catch (Exception exception) {
            throw new AgentUnavailableException("智能助手连接失败：" + rootMessage(exception), exception);
        }

        if (response.statusCode() != 200) {
            String message = statusMessage(response.statusCode());
            log.warn("agent 返回 {}: {}", response.statusCode(), message);
            listener.onError(message);
            return;
        }

        boolean finished = false;
        try (Stream<String> lines = response.body()) {
            Iterator<String> iterator = lines.iterator();
            while (iterator.hasNext()) {
                if (System.currentTimeMillis() > deadline) {
                    listener.onError("智能助手响应超时，请缩小问题范围后重试");
                    return;
                }
                String line = iterator.next();
                if (!line.startsWith("data:")) {
                    continue;
                }
                String payload = line.substring(5).strip();
                if (payload.isEmpty()) {
                    continue;
                }
                JsonNode event = MAPPER.readTree(payload);
                switch (event.path("type").asText("")) {
                    case "delta" -> listener.onDelta(event.path("text").asText(""));
                    case "tool" -> listener.onTool(
                            event.path("name").asText(""), event.path("status").asText(""));
                    case "error" -> {
                        listener.onError(event.path("message").asText("智能助手处理失败"));
                        return;
                    }
                    case "done" -> {
                        finished = true;
                        listener.onComplete(event.path("text").asText(""));
                        return;
                    }
                    default -> {
                        // start / usage / confirm_required：聊天里没有对应展示，忽略
                    }
                }
            }
            if (!finished) {
                listener.onComplete("");
            }
        } catch (AgentUnavailableException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new AgentUnavailableException("智能助手响应中断：" + rootMessage(exception), exception);
        }
    }

    private String body(String prompt, String sessionId) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("message", prompt);
            payload.put("session_id", sessionId);
            payload.put("scene", "");
            payload.put("env", properties.env());
            return MAPPER.writeValueAsString(payload);
        } catch (Exception exception) {
            throw new AgentUnavailableException("智能助手请求序列化失败", exception);
        }
    }

    private HttpRequest.Builder withIdentity(HttpRequest.Builder builder, AgentCaller caller) {
        identityHeaders(caller).forEach(builder::header);
        return builder;
    }

    private Map<String, String> identityHeaders(AgentCaller caller) {
        IdentitySigner signer = signerProvider.getIfAvailable();
        if (signer == null || !signer.isConfigured()) {
            throw new AgentUnavailableException("身份互信密钥未配置，无法调用智能助手");
        }
        long expiresAt = Instant.now().getEpochSecond() + HEADER_TTL_SECONDS;
        String canonical = signer.canonical(
                caller.userId(),
                caller.username(),
                caller.nickname(),
                caller.role(),
                caller.roles(),
                caller.permissions(),
                expiresAt);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(IdentityHeaders.USER_ID, String.valueOf(caller.userId()));
        headers.put(IdentityHeaders.USERNAME, encode(caller.username()));
        headers.put(IdentityHeaders.NICKNAME, encode(caller.nickname()));
        headers.put(IdentityHeaders.ROLE, encode(caller.role()));
        headers.put(IdentityHeaders.ROLES, String.join(",", caller.roles()));
        headers.put(IdentityHeaders.PERMISSIONS, String.join(",", caller.permissions()));
        headers.put(IdentityHeaders.EXPIRES_AT, String.valueOf(expiresAt));
        headers.put(IdentityHeaders.SIGNATURE, signer.sign(canonical));
        return headers;
    }

    private static String statusMessage(int status) {
        return switch (status) {
            case 401 -> "智能助手身份校验失败，请重新登录后再试";
            case 403 -> "没有使用智能助手的权限（需要 agent.chat.use）";
            case 429 -> "智能助手正忙，请稍后再试";
            case 503 -> "智能助手暂不可用，请稍后再试";
            default -> "智能助手调用失败（HTTP " + status + "）";
        };
    }

    private URI uri(String path) {
        String base = properties.baseUrl();
        String trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return URI.create(trimmed + path);
    }

    private static String encode(String value) {
        return B64.encodeToString((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
