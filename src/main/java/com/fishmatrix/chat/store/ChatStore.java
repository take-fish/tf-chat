package com.fishmatrix.chat.store;

import com.fishmatrix.chat.model.ChatEvent;
import com.fishmatrix.chat.model.ChatMessage;
import com.fishmatrix.chat.model.OnlineMember;
import java.util.List;

/**
 * 实时状态存储：在线成员、群内 presence、离线私聊队列与跨节点广播。
 * 消息历史与已读状态在 MySQL（ChatMessageRepository）。
 */
public interface ChatStore {

    String EVENTS_CHANNEL = "tf:chat:events";

    void addOfflineMessage(String userId, ChatMessage message);

    List<ChatMessage> drainOfflineMessages(String userId);

    void markOnline(OnlineMember member, long ttlMs);

    boolean isOnline(String userId);

    void markOffline(String userId);

    void upsertPresence(OnlineMember member, long ttlMs);

    void removePresence(String groupId, String userId);

    List<OnlineMember> members(String groupId);

    List<OnlineMember> onlineMembers();

    void publish(ChatEvent event);
}
