package com.fishmatrix.chat.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishmatrix.chat.model.ChatMessage;
import com.fishmatrix.chat.model.ChatUser;
import com.fishmatrix.chat.model.OnlineMember;
import com.fishmatrix.chat.service.ChatService;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);

    private final ChatService chatService;
    private final ChatSessionRegistry registry;
    private final ObjectMapper objectMapper;

    public ChatWebSocketHandler(
            ChatService chatService,
            ChatSessionRegistry registry,
            ObjectMapper objectMapper) {
        this.chatService = chatService;
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Map<String, String> query = queryOf(session);
        String userId = query.get("userId");
        String rawNickname = query.get("nickname");
        try {
            ChatService.requireUserId(userId);
            String nickname = chatService.normalizeNickname(rawNickname);
            chatService.connect(userId, nickname);
            ChatSession chatSession = registry.register(session, userId, nickname);
            registry.send(
                    chatSession,
                    Map.of(
                            "type",
                            "ready",
                            "user",
                            new ChatUser(userId, nickname, chatSession.getNode()),
                            "serverTime",
                            System.currentTimeMillis()));
            List<ChatMessage> offline = chatService.drainOffline(userId);
            if (!offline.isEmpty()) {
                registry.send(chatSession, Map.of("type", "dm_offline", "messages", offline));
            }
            log.info("聊天连接建立: {} ({})", nickname, userId);
        } catch (IllegalArgumentException e) {
            sendError(session, "INVALID_USER", e.getMessage());
            closeQuietly(session, CloseStatus.BAD_DATA);
        } catch (DataAccessException e) {
            sendError(session, "CHAT_UNAVAILABLE", "消息通道暂不可用");
            closeQuietly(session, CloseStatus.SERVER_ERROR);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        ChatSession chatSession = registry.get(session.getId());
        if (chatSession == null) {
            return;
        }
        try {
            WsInbound inbound = objectMapper.readValue(message.getPayload(), WsInbound.class);
            String type = inbound.type() == null ? "" : inbound.type();
            switch (type) {
                case "ping" -> handlePing(chatSession, inbound);
                case "join" -> handleJoin(chatSession, inbound);
                case "message" -> handleGroupMessage(chatSession, inbound);
                case "dm" -> handleDirectMessage(chatSession, inbound);
                case "leave" -> handleLeave(chatSession);
                default -> sendError(chatSession, "UNKNOWN_TYPE", "不支持的消息类型");
            }
        } catch (IllegalArgumentException e) {
            sendError(chatSession, "BAD_REQUEST", e.getMessage());
        } catch (DataAccessException e) {
            sendError(chatSession, "CHAT_UNAVAILABLE", "消息通道暂不可用");
        } catch (Exception e) {
            log.warn("处理 WebSocket 消息失败", e);
            sendError(chatSession, "INTERNAL_ERROR", "消息处理失败");
        }
    }

    private void handlePing(ChatSession session, WsInbound inbound) {
        chatService.heartbeat(session.getUserId(), session.getNickname(), session.getGroupId());
        long ts = inbound.ts() == null ? System.currentTimeMillis() : inbound.ts();
        registry.send(session, Map.of("type", "pong", "ts", ts));
    }

    private void handleJoin(ChatSession session, WsInbound inbound) {
        String groupId = inbound.groupId();
        chatService.requireGroup(groupId);
        String previous = session.getGroupId();
        if (previous != null && !previous.equals(groupId)) {
            chatService.leaveGroup(previous, session.getUserId());
        }
        session.setGroupId(groupId);
        chatService.joinGroup(groupId, session.getUserId(), session.getNickname());
        List<OnlineMember> members = chatService.members(groupId);
        registry.send(
                session,
                Map.of("type", "presence", "groupId", groupId, "members", members));
    }

    private void handleGroupMessage(ChatSession session, WsInbound inbound) {
        String groupId = session.getGroupId();
        if (groupId == null) {
            throw new IllegalArgumentException("请先加入会话组");
        }
        chatService.sendGroupMessage(
                groupId,
                session.getUserId(),
                session.getNickname(),
                inbound.content(),
                inbound.kind(),
                inbound.clientMsgId());
    }

    private void handleDirectMessage(ChatSession session, WsInbound inbound) {
        ChatMessage message = chatService.sendDirectMessage(
                inbound.toUserId(),
                session.getUserId(),
                session.getNickname(),
                inbound.content(),
                inbound.kind(),
                inbound.clientMsgId());
        // 接收者通过 Redis relay 收到；发送者在此直接回显
        registry.send(session, Map.of("type", "dm", "message", message));
    }

    private void handleLeave(ChatSession session) {
        String groupId = session.getGroupId();
        if (groupId != null) {
            session.setGroupId(null);
            chatService.leaveGroup(groupId, session.getUserId());
        }
        registry.send(session, Map.of("type", "left"));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        ChatSession chatSession = registry.remove(session.getId());
        if (chatSession == null) {
            return;
        }
        String groupId = chatSession.getGroupId();
        if (groupId != null) {
            boolean stillInGroup = registry.byGroup(groupId).stream()
                    .anyMatch(other -> other.getUserId().equals(chatSession.getUserId()));
            if (!stillInGroup) {
                chatService.leaveGroup(groupId, chatSession.getUserId());
            }
        }
        if (registry.byUser(chatSession.getUserId()).isEmpty()) {
            chatService.disconnect(chatSession.getUserId());
        }
        log.info("聊天连接关闭: {} ({})", chatSession.getNickname(), status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("WebSocket 传输错误: {}", exception.getMessage());
        closeQuietly(session, CloseStatus.SERVER_ERROR);
    }

    private void sendError(ChatSession session, String code, String message) {
        registry.send(
                session,
                Map.of("type", "error", "code", code, "message", message));
    }

    private void sendError(WebSocketSession session, String code, String message) {
        registry.sendRaw(
                session,
                Map.of("type", "error", "code", code, "message", message));
    }

    private void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            if (session.isOpen()) {
                session.close(status);
            }
        } catch (Exception ignored) {
            // 忽略关闭异常
        }
    }

    private Map<String, String> queryOf(WebSocketSession session) {
        if (session.getUri() == null) {
            return Map.of();
        }
        var params = UriComponentsBuilder.fromUri(session.getUri()).build().getQueryParams();
        return Map.of(
                "userId", decode(params.getFirst("userId")),
                "nickname", decode(params.getFirst("nickname")));
    }

    private String decode(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    public record WsInbound(
            String type,
            String groupId,
            String toUserId,
            String content,
            String kind,
            String clientMsgId,
            Long ts) {
    }
}
