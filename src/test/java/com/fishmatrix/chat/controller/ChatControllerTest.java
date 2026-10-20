package com.fishmatrix.chat.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fishmatrix.chat.auth.ChatAuthInterceptor;
import com.fishmatrix.chat.auth.WsTicketService;
import com.fishmatrix.chat.model.ChatGroupView;
import com.fishmatrix.chat.model.ChatIdentity;
import com.fishmatrix.chat.model.ChatMessage;
import com.fishmatrix.chat.model.OnlineMember;
import com.fishmatrix.chat.repository.ChatMessageRepository.HistoryPage;
import com.fishmatrix.chat.repository.ChatMessageRepository.UnreadSummary;
import com.fishmatrix.chat.service.ChatService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ChatControllerTest {

    private static final ChatIdentity VIEWER = ChatIdentity.of(7, "甲", "USER");

    private ChatService chatService;
    private WsTicketService ticketService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        chatService = mock(ChatService.class);
        ticketService = mock(WsTicketService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new ChatController(chatService, ticketService))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void returnsGroups() throws Exception {
        when(chatService.groups(VIEWER)).thenReturn(List.of(
                new ChatGroupView("dev-group", "研发协作", "日常研发", 2, 3)));

        mockMvc.perform(get("/api/v1/chat/groups")
                        .requestAttr(ChatAuthInterceptor.ATTR_IDENTITY, VIEWER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups[0].id").value("dev-group"))
                .andExpect(jsonPath("$.groups[0].onlineCount").value(2))
                .andExpect(jsonPath("$.groups[0].unread").value(3));
    }

    @Test
    void returnsPagedGroupHistory() throws Exception {
        ChatMessage message = message("m-1", 11, "dev-group", "hi");
        when(chatService.groupHistory("dev-group", 11L, null, 5))
                .thenReturn(new HistoryPage(List.of(message), true, 11L));

        mockMvc.perform(get("/api/v1/chat/groups/dev-group/history")
                        .param("limit", "5")
                        .param("beforeSeq", "11")
                        .requestAttr(ChatAuthInterceptor.ATTR_IDENTITY, VIEWER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[0].id").value("m-1"))
                .andExpect(jsonPath("$.messages[0].seq").value(11))
                .andExpect(jsonPath("$.hasMore").value(true))
                .andExpect(jsonPath("$.nextBeforeSeq").value(11));
    }

    @Test
    void returnsDirectHistoryForAuthenticatedViewer() throws Exception {
        ChatMessage message = message("m-2", 3, null, "hi");
        when(chatService.directHistory(VIEWER, "u9", null, 5L, 50))
                .thenReturn(new HistoryPage(List.of(message), false, null));

        mockMvc.perform(get("/api/v1/chat/dm/u9/history")
                        .param("afterSeq", "5")
                        .requestAttr(ChatAuthInterceptor.ATTR_IDENTITY, VIEWER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[0].content").value("hi"))
                .andExpect(jsonPath("$.hasMore").value(false));

        verify(chatService).directHistory(VIEWER, "u9", null, 5L, 50);
    }

    @Test
    void searchesGroupAndDirectMessages() throws Exception {
        when(chatService.groupSearch("dev-group", "build", 50))
                .thenReturn(List.of(message("m-3", 4, "dev-group", "build passed")));
        when(chatService.directSearch(VIEWER, "u9", "build", 50))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/v1/chat/groups/dev-group/search")
                        .param("q", "build")
                        .requestAttr(ChatAuthInterceptor.ATTR_IDENTITY, VIEWER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[0].content").value("build passed"));

        mockMvc.perform(get("/api/v1/chat/dm/u9/search")
                        .param("q", "build")
                        .requestAttr(ChatAuthInterceptor.ATTR_IDENTITY, VIEWER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages").isEmpty());
    }

    @Test
    void returnsOnlineAndUnreadSummary() throws Exception {
        when(chatService.onlineMembers()).thenReturn(List.of(
                new OnlineMember(null, "u7", "甲", "dev-node-01", 1L)));
        when(chatService.unread(VIEWER))
                .thenReturn(new UnreadSummary(Map.of("dev-group", 2), Map.of("u9", 1), 3));

        mockMvc.perform(get("/api/v1/chat/online")
                        .requestAttr(ChatAuthInterceptor.ATTR_IDENTITY, VIEWER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.members[0].userId").value("u7"));

        mockMvc.perform(get("/api/v1/chat/unread")
                        .requestAttr(ChatAuthInterceptor.ATTR_IDENTITY, VIEWER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups.dev-group").value(2))
                .andExpect(jsonPath("$.dms.u9").value(1))
                .andExpect(jsonPath("$.total").value(3));
    }

    @Test
    void marksReadAndRecalls() throws Exception {
        ChatMessage recalled = new ChatMessage(
                "m-4", 8, "dev-group", "u7", "甲", "dev-node-01", null,
                "", "text", 1000L, null, true);
        when(chatService.recall(VIEWER, "m-4")).thenReturn(recalled);

        mockMvc.perform(post("/api/v1/chat/read")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupId\":\"dev-group\",\"seq\":8}")
                        .requestAttr(ChatAuthInterceptor.ATTR_IDENTITY, VIEWER))
                .andExpect(status().isNoContent());
        verify(chatService).markRead(VIEWER, "dev-group", null, 8L);

        mockMvc.perform(post("/api/v1/chat/messages/m-4/recall")
                        .requestAttr(ChatAuthInterceptor.ATTR_IDENTITY, VIEWER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message.recalled").value(true));
    }

    @Test
    void issuesWsTicket() throws Exception {
        when(ticketService.issue(VIEWER))
                .thenReturn(new WsTicketService.Ticket("ticket-value", 60));

        mockMvc.perform(post("/api/v1/chat/ws-ticket")
                        .requestAttr(ChatAuthInterceptor.ATTR_IDENTITY, VIEWER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticket").value("ticket-value"))
                .andExpect(jsonPath("$.expiresIn").value(60));
    }

    @Test
    void mapsIllegalArgumentToBadRequest() throws Exception {
        when(chatService.groupHistory(eq("nope"), any(), any(), anyInt()))
                .thenThrow(new IllegalArgumentException("群组不存在"));

        mockMvc.perform(get("/api/v1/chat/groups/nope/history")
                        .requestAttr(ChatAuthInterceptor.ATTR_IDENTITY, VIEWER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    private static ChatMessage message(String id, long seq, String groupId, String content) {
        return new ChatMessage(
                id, seq, groupId, "u7", "甲", "dev-node-01",
                groupId == null ? "u9" : null, content, "text", 1000L, null, false);
    }
}
