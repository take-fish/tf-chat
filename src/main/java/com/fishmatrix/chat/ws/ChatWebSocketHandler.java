package com.fishmatrix.chat.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishmatrix.chat.model.ChatException;
import com.fishmatrix.chat.model.ChatIdentity;
import com.fishmatrix.chat.model.ChatMessage;
import com.fishmatrix.chat.model.ChatUser;
import com.fishmatrix.chat.model.OnlineMember;
import com.fishmatrix.chat.service.ChatService;
import com.fishmatrix.common.trace.Traces;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    public static final String ATTR_IDENTITY = "chat.identity";
    public static final String ATTR_TRACE_CONTEXT = "chat.traceContext";

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);

    private final ChatService chatService;
    private final ChatSessionRegistry registry;
    private final ObjectMapper objectMapper;
    private final Tracer tracer;

    public ChatWebSocketHandler(
            ChatService chatService,
            ChatSessionRegistry registry,
            ObjectMapper objectMapper) {
        this(chatService, registry, objectMapper, (Tracer) null);
    }

    @Autowired
    public ChatWebSocketHandler(
            ChatService chatService,
            ChatSessionRegistry registry,
            ObjectMapper objectMapper,
            ObjectProvider<Tracer> tracerProvider) {
        this(chatService, registry, objectMapper, tracerProvider.getIfAvailable());
    }

    private ChatWebSocketHandler(
            ChatService chatService,
            ChatSessionRegistry registry,
            ObjectMapper objectMapper,
            Tracer tracer) {
        this.chatService = chatService;
        this.registry = registry;
        this.objectMapper = objectMapper;
        this.tracer = tracer;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        try (var span = startSpan(session, "ws.connect")) {
            ChatIdentity identity = identityOf(session);
            if (identity == null) {
                closeQuietly(session, CloseStatus.POLICY_VIOLATION);
                return;
            }
            try {
                chatService.connect(identity);
                ChatSession chatSession = registry.register(
                        session, identity.userId(), identity.nickname());
                registry.send(
                        chatSession,
                        Map.of(
                                "type",
                                "ready",
                                "user",
                                new ChatUser(
                                        identity.userId(),
                                        identity.nickname(),
                                        chatSession.getNode()),
                                "serverTime",
                                System.currentTimeMillis()));
                List<ChatMessage> offline = chatService.drainOffline(identity);
                if (!offline.isEmpty()) {
                    registry.send(chatSession, Map.of("type", "dm_offline", "messages", offline));
                }
                log.info("聊天连接建立: {} ({})", identity.nickname(), identity.userId());
            } catch (ChatException exception) {
                sendError(session, exception.getCode(), exception.getMessage());
                closeQuietly(session, CloseStatus.BAD_DATA);
            } catch (DataAccessException exception) {
                sendError(session, "CHAT_UNAVAILABLE", "消息通道暂不可用");
                closeQuietly(session, CloseStatus.SERVER_ERROR);
            }
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        ChatSession chatSession = registry.get(session.getId());
        if (chatSession == null) {
            return;
        }
        try (var span = startSpan(session, "ws.message")) {
            try {
                WsInbound inbound = objectMapper.readValue(message.getPayload(), WsInbound.class);
                String type = inbound.type() == null ? "" : inbound.type();
                switch (type) {
                    case "ping" -> handlePing(chatSession, inbound);
                    case "join" -> handleJoin(chatSession, inbound);
                    case "message" -> handleGroupMessage(chatSession, inbound);
                    case "dm" -> handleDirectMessage(chatSession, inbound);
                    case "leave" -> handleLeave(chatSession);
                    case "read" -> handleRead(chatSession, inbound);
                    case "recall" -> handleRecall(chatSession, inbound);
                    default -> sendError(chatSession, "UNKNOWN_TYPE", "不支持的消息类型");
                }
            } catch (ChatException exception) {
                sendError(chatSession, exception.getCode(), exception.getMessage());
            } catch (IllegalArgumentException exception) {
                sendError(chatSession, "BAD_REQUEST", exception.getMessage());
            } catch (DataAccessException exception) {
                sendError(chatSession, "CHAT_UNAVAILABLE", "消息通道暂不可用");
            } catch (Exception exception) {
                log.warn("处理 WebSocket 消息失败", exception);
                sendError(chatSession, "INTERNAL_ERROR", "消息处理失败");
            }
        }
    }

    private void handlePing(ChatSession session, WsInbound inbound) {
        chatService.heartbeat(identityOf(session), session.getGroupId());
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
        chatService.joinGroup(identityOf(session), groupId);
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
                identityOf(session),
                groupId,
                inbound.content(),
                inbound.kind(),
                inbound.clientMsgId());
    }

    private void handleDirectMessage(ChatSession session, WsInbound inbound) {
        ChatMessage message = chatService.sendDirectMessage(
                identityOf(session),
                inbound.toUserId(),
                inbound.content(),
                inbound.kind(),
                inbound.clientMsgId());
        // 接收者通过 Redis relay 收到；发送者在此直接回显
        registry.send(session, Map.of("type", "dm", "message", message));
    }

    private void handleRead(ChatSession session, WsInbound inbound) {
        chatService.markRead(
                identityOf(session),
                inbound.groupId(),
                inbound.peerUserId(),
                inbound.seq());
    }

    private void handleRecall(ChatSession session, WsInbound inbound) {
        ChatMessage message = chatService.recall(identityOf(session), inbound.messageId());
        registry.send(
                session,
                Map.of(
                        "type", "recalled",
                        "message", message,
                        "byUserId", session.getUserId()));
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
        try (var span = startSpan(session, "ws.close")) {
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
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        try (var span = startSpan(session, "ws.transport_error")) {
            log.debug("WebSocket 传输错误: {}", exception.getMessage());
            closeQuietly(session, CloseStatus.SERVER_ERROR);
        }
    }

    private Traces.SpanScope startSpan(WebSocketSession session, String name) {
        if (tracer == null) {
            return null;
        }
        Object value = session.getAttributes().get(ATTR_TRACE_CONTEXT);
        TraceContext parent = value instanceof TraceContext context ? context : null;
        return Traces.startSpan(tracer, parent, name);
    }

    private ChatIdentity identityOf(WebSocketSession session) {
        Object value = session.getAttributes().get(ATTR_IDENTITY);
        return value instanceof ChatIdentity identity ? identity : null;
    }

    private ChatIdentity identityOf(ChatSession session) {
        return identityOf(session.getWebSocketSession());
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

    public record WsInbound(
            String type,
            String groupId,
            String peerUserId,
            String toUserId,
            String messageId,
            Long seq,
            String content,
            String kind,
            String clientMsgId,
            Long ts) {
    }
}
