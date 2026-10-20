package com.fishmatrix.chat.model;

public record ChatMessage(
        String id,
        long seq,
        String groupId,
        String fromUserId,
        String fromNickname,
        String fromNode,
        String toUserId,
        String content,
        String kind,
        long ts,
        String clientMsgId,
        boolean recalled) {

    public static final String KIND_TEXT = "text";
    public static final String KIND_ASCII = "ascii";
}
