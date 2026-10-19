package com.fishmatrix.chat.store;

import com.fishmatrix.chat.model.ChatEvent;
import com.fishmatrix.chat.model.ChatGroup;
import com.fishmatrix.chat.model.ChatMessage;
import com.fishmatrix.chat.model.OnlineMember;
import java.util.List;

public interface ChatStore {

    String EVENTS_CHANNEL = "tf:chat:events";

    void ensureGroups();

    List<ChatGroup> groups();

    boolean groupExists(String groupId);

    ChatMessage appendGroupMessage(String groupId, ChatMessage message);

    List<ChatMessage> groupHistory(String groupId, int limit);

    ChatMessage appendDirectMessage(ChatMessage message);

    List<ChatMessage> directHistory(String userA, String userB, int limit);

    void addOfflineMessage(String userId, ChatMessage message);

    List<ChatMessage> drainOfflineMessages(String userId);

    void markOnline(OnlineMember member, long ttlMs);

    boolean isOnline(String userId);

    void markOffline(String userId);

    void upsertPresence(OnlineMember member, long ttlMs);

    void removePresence(String groupId, String userId);

    List<OnlineMember> members(String groupId);

    void publish(ChatEvent event);
}
