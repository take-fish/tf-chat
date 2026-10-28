package com.fishmatrix.chat.ws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishmatrix.chat.util.NodeNames;
import com.fishmatrix.common.trace.TraceIds;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

@Component
@RequiredArgsConstructor
@Slf4j
public class ChatSessionRegistry {

    private final Map<String, ChatSession> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public ChatSession register(WebSocketSession webSocketSession, String userId, String nickname) {
        ChatSession session = new ChatSession(
                webSocketSession,
                userId,
                nickname,
                NodeNames.forUser(userId));
        sessions.put(webSocketSession.getId(), session);
        return session;
    }

    public ChatSession get(String sessionId) {
        return sessions.get(sessionId);
    }

    public ChatSession remove(String sessionId) {
        return sessions.remove(sessionId);
    }

    public List<ChatSession> byUser(String userId) {
        return sessions.values().stream()
                .filter(session -> session.getUserId().equals(userId))
                .toList();
    }

    public List<ChatSession> byGroup(String groupId) {
        return sessions.values().stream()
                .filter(session -> groupId.equals(session.getGroupId()))
                .toList();
    }

    public List<ChatSession> all() {
        return List.copyOf(sessions.values());
    }

    public void send(ChatSession session, Object payload) {
        sendRaw(session.getWebSocketSession(), payload);
    }

    public void sendRaw(WebSocketSession session, Object payload) {
        if (session == null || !session.isOpen()) {
            return;
        }
        try {
            String body = objectMapper.writeValueAsString(stampTrace(payload));
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(body));
                }
            }
        } catch (JsonProcessingException e) {
            log.warn("WebSocket 消息序列化失败", e);
        } catch (IOException e) {
            log.debug("WebSocket 发送失败（连接可能已关闭）: {}", e.getMessage());
        }
    }

    /**
     * 每个出站帧都带上当前链路 ID，前端可直接展示并用于日志检索。
     */
    private Object stampTrace(Object payload) {
        if (!(payload instanceof Map<?, ?> map) || map.containsKey("traceId")) {
            return payload;
        }
        String traceId = TraceIds.currentTraceId();
        if (traceId == null || traceId.isBlank()) {
            return payload;
        }
        Map<String, Object> stamped = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            stamped.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        stamped.put("traceId", traceId);
        return stamped;
    }
}
