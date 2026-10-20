package com.fishmatrix.chat.util;

import java.util.Comparator;
import java.util.List;

/**
 * 会话索引：群聊 group:<groupId>，私聊 dm:<较小 userId>:<较大 userId>。
 * 私聊双方必须得到同一个 conversationId，便于单索引分页与未读统计。
 */
public final class ConversationIds {

    private ConversationIds() {
    }

    public static String group(String groupId) {
        return "group:" + groupId;
    }

    public static String direct(String userA, String userB) {
        List<String> users = List.of(userA, userB).stream().sorted(Comparator.naturalOrder()).toList();
        return "dm:" + users.get(0) + ":" + users.get(1);
    }

    public static String peerOf(String conversationId, String viewerUserId) {
        if (conversationId == null || !conversationId.startsWith("dm:")) {
            return null;
        }
        String[] parts = conversationId.substring(3).split(":", 2);
        if (parts.length != 2) {
            return null;
        }
        return viewerUserId.equals(parts[0]) ? parts[1] : parts[0];
    }
}
