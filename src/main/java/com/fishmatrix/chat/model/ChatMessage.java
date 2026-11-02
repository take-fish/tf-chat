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
        boolean recalled,
        String streamId) {

    public static final String KIND_TEXT = "text";
    public static final String KIND_ASCII = "ascii";
    public static final String KIND_IMAGE = "image";

    /** 历史消息（库里读出来的）没有流式分段，单独给一个不带上游分片的构造入口。 */
    public ChatMessage(
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
        this(id, seq, groupId, fromUserId, fromNickname, fromNode, toUserId, content, kind, ts,
                clientMsgId, recalled, null);
    }

    /** 同一条消息发给不同收件人时，只换 toUserId（智能助手在私聊里对双方各发一份）。 */
    public ChatMessage withTo(String toUserId) {
        return new ChatMessage(id, seq, groupId, fromUserId, fromNickname, fromNode, toUserId,
                content, kind, ts, clientMsgId, recalled, streamId);
    }
}
