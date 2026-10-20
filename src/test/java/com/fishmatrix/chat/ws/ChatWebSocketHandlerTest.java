package com.fishmatrix.chat.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishmatrix.chat.model.ChatException;
import com.fishmatrix.chat.model.ChatIdentity;
import com.fishmatrix.chat.model.ChatMessage;
import com.fishmatrix.chat.service.ChatService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

class ChatWebSocketHandlerTest {

    private static final ChatIdentity ALICE = ChatIdentity.of(1, "甲", "USER");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ChatService chatService;
    private ChatSessionRegistry registry;
    private ChatWebSocketHandler handler;
    private WebSocketSession socket;
    private List<JsonNode> sent;

    @BeforeEach
    void setUp() throws Exception {
        chatService = mock(ChatService.class);
        registry = new ChatSessionRegistry(objectMapper);
        handler = new ChatWebSocketHandler(chatService, registry, objectMapper);
        sent = new ArrayList<>();
        socket = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(ChatWebSocketHandler.ATTR_IDENTITY, ALICE);
        when(socket.getId()).thenReturn("s-1");
        when(socket.getAttributes()).thenReturn(attributes);
        when(socket.isOpen()).thenReturn(true);
        doAnswer(invocation -> {
            TextMessage message = invocation.getArgument(0);
            sent.add(objectMapper.readTree(message.getPayload()));
            return null;
        }).when(socket).sendMessage(any(TextMessage.class));
    }

    @Test
    void closesConnectionWithoutTicketIdentity() throws Exception {
        WebSocketSession anonymous = mock(WebSocketSession.class);
        when(anonymous.getAttributes()).thenReturn(new HashMap<>());
        when(anonymous.isOpen()).thenReturn(true);

        handler.afterConnectionEstablished(anonymous);

        verify(anonymous).close(CloseStatus.POLICY_VIOLATION);
        verify(chatService, never()).connect(any());
    }

    @Test
    void sendsReadyAndOfflineMessagesOnConnect() {
        ChatMessage offline = message("m-offline", null, "u2", "离线消息");
        when(chatService.drainOffline(ALICE)).thenReturn(List.of(offline));

        handler.afterConnectionEstablished(socket);

        assertThat(sent).extracting(node -> node.get("type").asText())
                .containsExactly("ready", "dm_offline");
        assertThat(sent.get(0).get("user").get("id").asText()).isEqualTo("u1");
        assertThat(sent.get(0).get("user").get("nickname").asText()).isEqualTo("甲");
        assertThat(sent.get(1).get("messages").get(0).get("id").asText()).isEqualTo("m-offline");
        verify(chatService).connect(ALICE);
        assertThat(registry.get("s-1")).isNotNull();
    }

    @Test
    void handlesJoinSendReadAndRecall() throws Exception {
        registry.register(socket, "u1", "甲");
        ChatMessage recallResult = new ChatMessage(
                "m-1", 3, "dev-group", "u1", "甲", "dev-node-01", null,
                "", "text", 1L, null, true);
        when(chatService.recall(ALICE, "m-1")).thenReturn(recallResult);

        handler.handleTextMessage(socket, frame("""
                {"type":"join","groupId":"dev-group"}
                """));
        assertThat(registry.get("s-1").getGroupId()).isEqualTo("dev-group");
        verify(chatService).joinGroup(ALICE, "dev-group");

        handler.handleTextMessage(socket, frame("""
                {"type":"message","content":"hi","clientMsgId":"c-1"}
                """));
        verify(chatService).sendGroupMessage(ALICE, "dev-group", "hi", null, "c-1");

        handler.handleTextMessage(socket, frame("""
                {"type":"read","groupId":"dev-group","seq":9}
                """));
        verify(chatService).markRead(ALICE, "dev-group", null, 9L);

        handler.handleTextMessage(socket, frame("""
                {"type":"recall","messageId":"m-1"}
                """));
        assertThat(sent).anySatisfy(node -> {
            assertThat(node.get("type").asText()).isEqualTo("recalled");
            assertThat(node.get("message").get("recalled").asBoolean()).isTrue();
        });
    }

    @Test
    void echoesDirectMessageToSender() throws Exception {
        ChatMessage direct = message("m-dm", null, "u2", "hi");
        when(chatService.sendDirectMessage(ALICE, "u2", "hi", "text", "c-2"))
                .thenReturn(direct);
        registry.register(socket, "u1", "甲");

        handler.handleTextMessage(socket, frame("""
                {"type":"dm","toUserId":"u2","content":"hi","kind":"text","clientMsgId":"c-2"}
                """));

        assertThat(sent).singleElement().satisfies(node -> {
            assertThat(node.get("type").asText()).isEqualTo("dm");
            assertThat(node.get("message").get("id").asText()).isEqualTo("m-dm");
        });
    }

    @Test
    void returnsRateLimitErrorFrame() throws Exception {
        when(chatService.sendGroupMessage(ALICE, "dev-group", "spam", null, null))
                .thenThrow(ChatException.rateLimited("发送过于频繁，请稍后再试"));
        registry.register(socket, "u1", "甲");
        registry.get("s-1").setGroupId("dev-group");

        handler.handleTextMessage(socket, frame("""
                {"type":"message","content":"spam"}
                """));

        assertThat(sent).singleElement().satisfies(node -> {
            assertThat(node.get("type").asText()).isEqualTo("error");
            assertThat(node.get("code").asText()).isEqualTo("RATE_LIMITED");
        });
    }

    @Test
    void rejectsUnknownFrameTypeAndMissingGroup() throws Exception {
        registry.register(socket, "u1", "甲");

        handler.handleTextMessage(socket, frame("""
                {"type":"nope"}
                """));
        handler.handleTextMessage(socket, frame("""
                {"type":"message","content":"hi"}
                """));

        assertThat(sent).extracting(node -> node.get("code").asText())
                .containsExactly("UNKNOWN_TYPE", "BAD_REQUEST");
        verify(chatService, never()).sendGroupMessage(any(), any(), any(), any(), any());
    }

    @Test
    void cleansPresenceWhenLastSessionCloses() {
        registry.register(socket, "u1", "甲");
        registry.get("s-1").setGroupId("dev-group");

        handler.afterConnectionClosed(socket, CloseStatus.NORMAL);

        verify(chatService).leaveGroup("dev-group", "u1");
        verify(chatService).disconnect("u1");
        assertThat(registry.get("s-1")).isNull();
    }

    private TextMessage frame(String json) {
        return new TextMessage(json);
    }

    private static ChatMessage message(String id, String groupId, String toUserId, String content) {
        return new ChatMessage(
                id, 1L, groupId, "u1", "甲", "dev-node-01", toUserId,
                content, "text", 1L, null, false);
    }
}
