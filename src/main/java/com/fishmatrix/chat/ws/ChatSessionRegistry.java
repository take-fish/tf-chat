package com.fishmatrix.chat.ws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishmatrix.chat.util.NodeNames;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

@Component
public class ChatSessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(ChatSessionRegistry.class);

    private final Map<String, ChatSession> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public ChatSessionRegistry(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

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

    public void send(ChatSession session, Object payload) {
        sendRaw(session.getWebSocketSession(), payload);
    }

    public void sendRaw(WebSocketSession session, Object payload) {
        if (session == null || !session.isOpen()) {
            return;
        }
        try {
            String body = objectMapper.writeValueAsString(payload);
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
}
