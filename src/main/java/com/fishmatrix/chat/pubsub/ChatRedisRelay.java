package com.fishmatrix.chat.pubsub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishmatrix.chat.model.ChatEvent;
import com.fishmatrix.chat.ws.ChatSessionRegistry;
import com.fishmatrix.common.trace.TraceIds;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

@Component
public class ChatRedisRelay implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(ChatRedisRelay.class);

    private final ChatSessionRegistry registry;
    private final ObjectMapper objectMapper;

    public ChatRedisRelay(ChatSessionRegistry registry, ObjectMapper objectMapper) {
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            ChatEvent event = objectMapper.readValue(body, ChatEvent.class);
            Map<String, String> previousMdc = MDC.getCopyOfContextMap();
            applyTrace(event.traceId());
            try {
                switch (event.type()) {
                    case "message" -> registry.byGroup(event.target()).forEach(session ->
                            registry.send(
                                    session,
                                    Map.of("type", "message", "message", event.message())));
                    case "dm" -> registry.byUser(event.target()).forEach(session ->
                            registry.send(
                                    session,
                                    Map.of("type", "dm", "message", event.message())));
                    case "presence" -> registry.byGroup(event.target()).forEach(session ->
                            registry.send(
                                    session,
                                    Map.of(
                                            "type", "presence",
                                            "groupId", event.target(),
                                            "members", event.members())));
                    case "recalled" -> {
                        List<com.fishmatrix.chat.ws.ChatSession> targets =
                                event.message() != null && event.message().groupId() != null
                                        ? registry.byGroup(event.target())
                                        : registry.byUser(event.target());
                        targets.forEach(session -> registry.send(
                                session,
                                Map.of(
                                        "type", "recalled",
                                        "message", event.message(),
                                        "byUserId", event.message() == null
                                                ? ""
                                                : event.message().fromUserId())));
                    }
                    default -> log.debug("忽略未知聊天事件: {}", event.type());
                }
            } finally {
                if (previousMdc == null) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(previousMdc);
                }
            }
        } catch (Exception exception) {
            log.warn("处理 Redis 聊天事件失败: {}", exception.getMessage());
        }
    }

    private static void applyTrace(String traceId) {
        if (traceId != null && !traceId.isBlank()) {
            MDC.put(TraceIds.MDC_TRACE_ID, traceId);
            MDC.put(TraceIds.MDC_REQUEST_ID, traceId);
        }
    }
}
