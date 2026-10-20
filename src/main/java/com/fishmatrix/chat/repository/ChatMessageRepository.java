package com.fishmatrix.chat.repository;

import com.fishmatrix.chat.model.ChatGroup;
import com.fishmatrix.chat.model.ChatMessage;
import java.util.List;
import java.util.Map;

public interface ChatMessageRepository {

    void ensureGroups();

    List<ChatGroup> groups();

    boolean groupExists(String groupId);

    /**
     * 按 (fromUserId, clientMsgId) 幂等写入；重复消息返回 created=false，避免重复广播。
     */
    SaveResult insertIfAbsent(ChatMessage message);

    HistoryPage groupHistory(String groupId, Long beforeSeq, Long afterSeq, int limit);

    HistoryPage directHistory(String userA, String userB, Long beforeSeq, Long afterSeq, int limit);

    List<ChatMessage> searchGroup(String groupId, String keyword, int limit);

    List<ChatMessage> searchDirect(String userA, String userB, String keyword, int limit);

    ChatMessage recall(String messageId, String userId, long windowMs);

    void markRead(String userId, String groupId, String peerUserId, long seq);

    long latestSeq(String conversationId);

    UnreadSummary unread(String userId);

    record SaveResult(ChatMessage message, boolean created) {
    }

    record HistoryPage(List<ChatMessage> messages, boolean hasMore, Long nextBeforeSeq) {
    }

    record UnreadSummary(Map<String, Integer> groups, Map<String, Integer> dms, int total) {
    }
}
