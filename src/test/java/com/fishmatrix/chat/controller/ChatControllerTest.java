package com.fishmatrix.chat.controller;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fishmatrix.chat.model.ChatGroupView;
import com.fishmatrix.chat.model.ChatMessage;
import com.fishmatrix.chat.service.ChatService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ChatControllerTest {

    private ChatService chatService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        chatService = mock(ChatService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new ChatController(chatService))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void returnsGroups() throws Exception {
        when(chatService.groups())
                .thenReturn(List.of(new ChatGroupView("dev-group", "研发协作", "日常研发", 2)));

        mockMvc.perform(get("/api/v1/chat/groups"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups[0].id").value("dev-group"))
                .andExpect(jsonPath("$.groups[0].onlineCount").value(2));
    }

    @Test
    void returnsGroupHistory() throws Exception {
        when(chatService.groupHistory("dev-group", 5)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/chat/groups/dev-group/history").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages").isArray());
    }

    @Test
    void returnsDirectHistoryWithUserParam() throws Exception {
        ChatMessage message = new ChatMessage(
                "m-1", null, "user-a", "甲", "dev-node-01", "user-b", "hi", "text", 1L, null);
        when(chatService.directHistory("user-a", "user-b", 50)).thenReturn(List.of(message));

        mockMvc.perform(get("/api/v1/chat/dm/user-b/history").param("userId", "user-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[0].content").value("hi"));

        verify(chatService, org.mockito.Mockito.times(1))
                .directHistory(org.mockito.ArgumentMatchers.eq("user-a"),
                        org.mockito.ArgumentMatchers.eq("user-b"), anyInt());
    }

    @Test
    void mapsIllegalArgumentToBadRequest() throws Exception {
        when(chatService.groupHistory(anyString(), anyInt()))
                .thenThrow(new IllegalArgumentException("群组不存在"));

        mockMvc.perform(get("/api/v1/chat/groups/nope/history"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }
}
