package com.fishmatrix.chat.model;

import com.fishmatrix.common.trace.TraceIds;
import java.util.List;

/**
 * 跨节点聊天事件。traceId 随事件进入 Redis，另一节点投递 WebSocket 帧时仍能关联原请求。
 */
public record ChatEvent(
        String type,
        String target,
        ChatMessage message,
        List<OnlineMember> members,
        String traceId) {

    public static ChatEvent groupMessage(ChatMessage message) {
        return new ChatEvent("message", message.groupId(), message, null, TraceIds.currentTraceId());
    }

    public static ChatEvent directMessage(ChatMessage message) {
        return new ChatEvent("dm", message.toUserId(), message, null, TraceIds.currentTraceId());
    }

    public static ChatEvent presence(String groupId, List<OnlineMember> members) {
        return new ChatEvent("presence", groupId, null, members, TraceIds.currentTraceId());
    }

    public static ChatEvent recalled(String target, ChatMessage message) {
        return new ChatEvent("recalled", target, message, null, TraceIds.currentTraceId());
    }
}
