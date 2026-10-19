package com.fishmatrix.chat.model;

public record ChatMessage(
        String id,
        String groupId,
        String fromUserId,
        String fromNickname,
        String fromNode,
        String toUserId,
        String content,
        String kind,
        long ts,
        String clientMsgId) {
}
