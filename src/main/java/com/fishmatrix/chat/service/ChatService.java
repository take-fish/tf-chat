package com.fishmatrix.chat.service;

import com.fishmatrix.chat.config.ChatProperties;
import com.fishmatrix.chat.model.ChatEvent;
import com.fishmatrix.chat.model.ChatGroupView;
import com.fishmatrix.chat.model.ChatIdentity;
import com.fishmatrix.chat.model.ChatMessage;
import com.fishmatrix.chat.model.OnlineMember;
import com.fishmatrix.chat.repository.ChatMessageRepository;
import com.fishmatrix.chat.repository.ChatMessageRepository.HistoryPage;
import com.fishmatrix.chat.repository.ChatMessageRepository.SaveResult;
import com.fishmatrix.chat.repository.ChatMessageRepository.UnreadSummary;
import com.fishmatrix.chat.store.ChatStore;
import com.fishmatrix.chat.util.ConversationIds;
import com.fishmatrix.chat.util.NodeNames;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class ChatService {

    private static final Pattern USER_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern GROUP_ID = Pattern.compile("[a-z0-9-]{1,32}");
    private static final Pattern UNSAFE_CONTROL = Pattern.compile(
            "[\\u0000-\\u0008\\u000B-\\u001F\\u007F-\\u009F\\u202A-\\u202E\\u2066-\\u2069]");

    private final ChatStore store;
    private final ChatMessageRepository messages;
    private final ChatRateLimiter rateLimiter;
    private final ChatProperties properties;

    public ChatService(
            ChatStore store,
            ChatMessageRepository messages,
            ChatRateLimiter rateLimiter,
            ChatProperties properties) {
        this.store = store;
        this.messages = messages;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
    }

    public List<ChatGroupView> groups(ChatIdentity viewer) {
        Map<String, Integer> unread = messages.unread(viewer.userId()).groups();
        return messages.groups().stream()
                .map(group -> new ChatGroupView(
                        group.id(),
                        group.name(),
                        group.description(),
                        store.members(group.id()).size(),
                        unread.getOrDefault(group.id(), 0)))
                .toList();
    }

    public HistoryPage groupHistory(String groupId, Long beforeSeq, Long afterSeq, int limit) {
        requireGroup(groupId);
        requireCursor(beforeSeq, afterSeq);
        return messages.groupHistory(groupId, beforeSeq, afterSeq, clamp(limit, 1, 200));
    }

    public HistoryPage directHistory(
            ChatIdentity viewer,
            String peerUserId,
            Long beforeSeq,
            Long afterSeq,
            int limit) {
        requireUserId(peerUserId);
        requireNotSelf(viewer, peerUserId);
        requireCursor(beforeSeq, afterSeq);
        return messages.directHistory(
                viewer.userId(), peerUserId, beforeSeq, afterSeq, clamp(limit, 1, 200));
    }

    public List<ChatMessage> groupSearch(String groupId, String keyword, int limit) {
        requireGroup(groupId);
        return messages.searchGroup(groupId, requireKeyword(keyword), clamp(limit, 1, 100));
    }

    public List<ChatMessage> directSearch(
            ChatIdentity viewer,
            String peerUserId,
            String keyword,
            int limit) {
        requireUserId(peerUserId);
        requireNotSelf(viewer, peerUserId);
        return messages.searchDirect(
                viewer.userId(), peerUserId, requireKeyword(keyword), clamp(limit, 1, 100));
    }

    public List<OnlineMember> members(String groupId) {
        requireGroup(groupId);
        return store.members(groupId);
    }

    public List<OnlineMember> onlineMembers() {
        return store.onlineMembers();
    }

    public ChatMessage sendGroupMessage(
            ChatIdentity sender,
            String groupId,
            String content,
            String kind,
            String clientMsgId) {
        requireGroup(groupId);
        rateLimiter.acquire(sender.userId());
        ChatMessage message = newMessage(
                sender, groupId, null, content, kind, clientMsgId);
        SaveResult saved = messages.insertIfAbsent(message);
        if (saved.created()) {
            store.publish(ChatEvent.groupMessage(saved.message()));
        }
        return saved.message();
    }

    public ChatMessage sendDirectMessage(
            ChatIdentity sender,
            String toUserId,
            String content,
            String kind,
            String clientMsgId) {
        requireUserId(toUserId);
        requireNotSelf(sender, toUserId);
        rateLimiter.acquire(sender.userId());
        ChatMessage message = newMessage(
                sender, null, toUserId, content, kind, clientMsgId);
        SaveResult saved = messages.insertIfAbsent(message);
        if (saved.created()) {
            if (store.isOnline(toUserId)) {
                store.publish(ChatEvent.directMessage(saved.message()));
            } else {
                store.addOfflineMessage(toUserId, saved.message());
            }
        }
        return saved.message();
    }

    public ChatMessage recall(ChatIdentity identity, String messageId) {
        if (messageId == null || messageId.isBlank()) {
            throw new IllegalArgumentException("缺少消息 ID");
        }
        ChatMessage message = messages.recall(
                messageId.strip(),
                identity.userId(),
                properties.recallWindowSeconds() * 1000L);
        if (message.groupId() != null) {
            store.publish(ChatEvent.recalled(message.groupId(), message));
        } else {
            store.publish(ChatEvent.recalled(message.fromUserId(), message));
            if (message.toUserId() != null) {
                store.publish(ChatEvent.recalled(message.toUserId(), message));
            }
        }
        return message;
    }

    public void markRead(ChatIdentity viewer, String groupId, String peerUserId, Long seq) {
        if (groupId != null) {
            requireGroup(groupId);
            long target = seq == null
                    ? messages.latestSeq(ConversationIds.group(groupId))
                    : Math.max(0L, seq);
            messages.markRead(viewer.userId(), groupId, null, target);
            return;
        }
        if (peerUserId != null) {
            requireUserId(peerUserId);
            requireNotSelf(viewer, peerUserId);
            long target = seq == null
                    ? messages.latestSeq(ConversationIds.direct(viewer.userId(), peerUserId))
                    : Math.max(0L, seq);
            messages.markRead(viewer.userId(), null, peerUserId, target);
            return;
        }
        throw new IllegalArgumentException("缺少群组或私聊对象");
    }

    public UnreadSummary unread(ChatIdentity viewer) {
        return messages.unread(viewer.userId());
    }

    public void connect(ChatIdentity identity) {
        String nickname = normalizeNickname(identity.nickname());
        store.markOnline(
                new OnlineMember(
                        null,
                        identity.userId(),
                        nickname,
                        NodeNames.forUser(identity.userId()),
                        System.currentTimeMillis()),
                properties.presenceTtlMs());
    }

    public void heartbeat(ChatIdentity identity, String groupId) {
        String nickname = normalizeNickname(identity.nickname());
        long now = System.currentTimeMillis();
        store.markOnline(
                new OnlineMember(
                        null,
                        identity.userId(),
                        nickname,
                        NodeNames.forUser(identity.userId()),
                        now),
                properties.presenceTtlMs());
        if (groupId != null
                && GROUP_ID.matcher(groupId).matches()
                && messages.groupExists(groupId)) {
            store.upsertPresence(
                    new OnlineMember(
                            groupId,
                            identity.userId(),
                            nickname,
                            NodeNames.forUser(identity.userId()),
                            now),
                    properties.presenceTtlMs());
        }
    }

    public void joinGroup(ChatIdentity identity, String groupId) {
        requireGroup(groupId);
        String nickname = normalizeNickname(identity.nickname());
        store.upsertPresence(
                new OnlineMember(
                        groupId,
                        identity.userId(),
                        nickname,
                        NodeNames.forUser(identity.userId()),
                        System.currentTimeMillis()),
                properties.presenceTtlMs());
        store.publish(ChatEvent.presence(groupId, store.members(groupId)));
    }

    public void leaveGroup(String groupId, String userId) {
        if (groupId == null || groupId.isBlank()) {
            return;
        }
        store.removePresence(groupId, userId);
        store.publish(ChatEvent.presence(groupId, store.members(groupId)));
    }

    public List<ChatMessage> drainOffline(ChatIdentity identity) {
        return store.drainOfflineMessages(identity.userId());
    }

    public void disconnect(String userId) {
        store.markOffline(userId);
    }

    public void requireGroup(String groupId) {
        if (groupId == null
                || !GROUP_ID.matcher(groupId).matches()
                || !messages.groupExists(groupId)) {
            throw new IllegalArgumentException("群组不存在或未授权访问");
        }
    }

    public static void requireUserId(String userId) {
        if (userId == null || !USER_ID.matcher(userId).matches()) {
            throw new IllegalArgumentException("userId 格式不合法");
        }
    }

    public String normalizeNickname(String nickname) {
        String cleaned = nickname == null ? "" : nickname.strip().replaceAll("\\s+", " ");
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("昵称不能为空");
        }
        if (cleaned.codePointCount(0, cleaned.length()) > properties.maxNicknameLength()) {
            throw new IllegalArgumentException("昵称过长");
        }
        return cleaned;
    }

    private ChatMessage newMessage(
            ChatIdentity sender,
            String groupId,
            String toUserId,
            String content,
            String kind,
            String clientMsgId) {
        return new ChatMessage(
                UUID.randomUUID().toString(),
                0L,
                groupId,
                sender.userId(),
                normalizeNickname(sender.nickname()),
                NodeNames.forUser(sender.userId()),
                toUserId,
                normalizeContent(content),
                normalizeKind(kind),
                System.currentTimeMillis(),
                normalizeClientMsgId(clientMsgId),
                false);
    }

    private void requireCursor(Long beforeSeq, Long afterSeq) {
        if (beforeSeq != null && afterSeq != null) {
            throw new IllegalArgumentException("beforeSeq 与 afterSeq 不能同时使用");
        }
        if ((beforeSeq != null && beforeSeq < 0) || (afterSeq != null && afterSeq < 0)) {
            throw new IllegalArgumentException("游标不合法");
        }
    }

    private void requireNotSelf(ChatIdentity viewer, String peerUserId) {
        if (viewer.userId().equals(peerUserId)) {
            throw new IllegalArgumentException("不能与当前节点建立会话");
        }
    }

    private String requireKeyword(String keyword) {
        String cleaned = keyword == null ? "" : keyword.strip();
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("搜索关键字不能为空");
        }
        return cleaned.length() > 64 ? cleaned.substring(0, 64) : cleaned;
    }

    private String normalizeContent(String content) {
        String cleaned = content == null ? "" : content.replace("\r\n", "\n");
        cleaned = UNSAFE_CONTROL.matcher(cleaned).replaceAll("").strip();
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("消息内容不能为空");
        }
        if (cleaned.codePointCount(0, cleaned.length()) > properties.maxContentLength()) {
            throw new IllegalArgumentException("消息内容过长");
        }
        return cleaned;
    }

    private String normalizeKind(String kind) {
        return ChatMessage.KIND_ASCII.equalsIgnoreCase(kind)
                ? ChatMessage.KIND_ASCII
                : ChatMessage.KIND_TEXT;
    }

    private String normalizeClientMsgId(String clientMsgId) {
        if (clientMsgId == null) {
            return null;
        }
        String cleaned = clientMsgId.strip();
        if (cleaned.isEmpty()) {
            return null;
        }
        return cleaned.length() > 80 ? cleaned.substring(0, 80) : cleaned;
    }

    private int clamp(int value, int min, int max) {
        return Math.min(max, Math.max(min, value));
    }
}
