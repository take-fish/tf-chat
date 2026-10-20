package com.fishmatrix.chat.model;

import java.util.List;

public record ChatEvent(
        String type,
        String target,
        ChatMessage message,
        List<OnlineMember> members) {

    public static ChatEvent groupMessage(ChatMessage message) {
        return new ChatEvent("message", message.groupId(), message, null);
    }

    public static ChatEvent directMessage(ChatMessage message) {
        return new ChatEvent("dm", message.toUserId(), message, null);
    }

    public static ChatEvent presence(String groupId, List<OnlineMember> members) {
        return new ChatEvent("presence", groupId, null, members);
    }

    public static ChatEvent recalled(String target, ChatMessage message) {
        return new ChatEvent("recalled", target, message, null);
    }
}
