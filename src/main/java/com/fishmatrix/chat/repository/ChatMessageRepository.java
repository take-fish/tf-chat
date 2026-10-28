package com.fishmatrix.chat.repository;

import com.fishmatrix.chat.model.ChatGroup;
import com.fishmatrix.chat.model.ChatMessage;
import java.util.List;
import java.util.Map;

public interface ChatMessageRepository {

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

    ChatMessage findById(String messageId);

    /**
     * 条件撤回：仅当消息尚未撤回时写入，返回影响行数。
     * 撤回权限、群聊限制与时限由 Service 判断，仓储只负责持久化。
     */
    int markRecalled(String messageId, String userId, long now);

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
