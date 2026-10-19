package com.fishmatrix.chat.controller;

import com.fishmatrix.chat.model.ChatGroupView;
import com.fishmatrix.chat.model.ChatMessage;
import com.fishmatrix.chat.model.OnlineMember;
import com.fishmatrix.chat.service.ChatService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @GetMapping("/groups")
    public Map<String, Object> groups() {
        List<ChatGroupView> groups = chatService.groups();
        return Map.of("groups", groups);
    }

    @GetMapping("/groups/{groupId}/history")
    public Map<String, Object> history(
            @PathVariable String groupId,
            @RequestParam(defaultValue = "20") int limit) {
        List<ChatMessage> messages = chatService.groupHistory(groupId, limit);
        return Map.of("messages", messages);
    }

    @GetMapping("/groups/{groupId}/members")
    public Map<String, Object> members(@PathVariable String groupId) {
        List<OnlineMember> members = chatService.members(groupId);
        return Map.of("members", members, "count", members.size());
    }

    @GetMapping("/dm/{peerUserId}/history")
    public Map<String, Object> directHistory(
            @PathVariable String peerUserId,
            @RequestParam String userId,
            @RequestParam(defaultValue = "50") int limit) {
        List<ChatMessage> messages = chatService.directHistory(userId, peerUserId, limit);
        return Map.of("messages", messages);
    }
}
