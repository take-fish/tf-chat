package com.fishmatrix.chat.service;

import com.fishmatrix.chat.config.ChatProperties;
import com.fishmatrix.chat.model.ChatEvent;
import com.fishmatrix.chat.model.ChatGroupView;
import com.fishmatrix.chat.model.ChatMessage;
import com.fishmatrix.chat.model.OnlineMember;
import com.fishmatrix.chat.store.ChatStore;
import com.fishmatrix.chat.util.NodeNames;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class ChatService {

    private static final Pattern USER_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern GROUP_ID = Pattern.compile("[a-z0-9-]{1,32}");

    private final ChatStore store;
    private final ChatProperties properties;

    public ChatService(ChatStore store, ChatProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    public List<ChatGroupView> groups() {
        store.ensureGroups();
        return store.groups().stream()
                .map(group -> new ChatGroupView(
                        group.id(),
                        group.name(),
                        group.description(),
                        store.members(group.id()).size()))
                .toList();
    }

    public List<ChatMessage> groupHistory(String groupId, int limit) {
        requireGroup(groupId);
        return store.groupHistory(groupId, clamp(limit, 1, 200));
    }

    public List<OnlineMember> members(String groupId) {
        requireGroup(groupId);
        return store.members(groupId);
    }

    public List<ChatMessage> directHistory(String userId, String peerUserId, int limit) {
        requireUserId(userId);
        requireUserId(peerUserId);
        return store.directHistory(userId, peerUserId, clamp(limit, 1, 200));
    }

    public ChatMessage sendGroupMessage(
            String groupId,
            String fromUserId,
            String nickname,
            String content,
            String kind,
            String clientMsgId) {
        requireGroup(groupId);
        requireUserId(fromUserId);
        String cleanNickname = normalizeNickname(nickname);
        ChatMessage message = new ChatMessage(
                UUID.randomUUID().toString(),
                groupId,
                fromUserId,
                cleanNickname,
                NodeNames.forUser(fromUserId),
                null,
                normalizeContent(content),
                normalizeKind(kind),
                System.currentTimeMillis(),
                normalizeClientMsgId(clientMsgId));
        store.appendGroupMessage(groupId, message);
        store.publish(ChatEvent.groupMessage(message));
        return message;
    }

    public ChatMessage sendDirectMessage(
            String toUserId,
            String fromUserId,
            String nickname,
            String content,
            String kind,
            String clientMsgId) {
        requireUserId(toUserId);
        requireUserId(fromUserId);
        if (toUserId.equals(fromUserId)) {
            throw new IllegalArgumentException("不能向当前节点发送私聊");
        }
        String cleanNickname = normalizeNickname(nickname);
        ChatMessage message = new ChatMessage(
                UUID.randomUUID().toString(),
                null,
                fromUserId,
                cleanNickname,
                NodeNames.forUser(fromUserId),
                toUserId,
                normalizeContent(content),
                normalizeKind(kind),
                System.currentTimeMillis(),
                normalizeClientMsgId(clientMsgId));
        store.appendDirectMessage(message);
        if (store.isOnline(toUserId)) {
            store.publish(ChatEvent.directMessage(message));
        } else {
            store.addOfflineMessage(toUserId, message);
        }
        return message;
    }

    public void connect(String userId, String nickname) {
        requireUserId(userId);
        String cleanNickname = normalizeNickname(nickname);
        store.markOnline(
                new OnlineMember(null, userId, cleanNickname, NodeNames.forUser(userId), System.currentTimeMillis()),
                properties.presenceTtlMs());
    }

    public void heartbeat(String userId, String nickname, String groupId) {
        requireUserId(userId);
        store.ensureGroups();
        String cleanNickname = normalizeNickname(nickname);
        long now = System.currentTimeMillis();
        store.markOnline(
                new OnlineMember(null, userId, cleanNickname, NodeNames.forUser(userId), now),
                properties.presenceTtlMs());
        if (groupId != null && GROUP_ID.matcher(groupId).matches() && store.groupExists(groupId)) {
            store.upsertPresence(
                    new OnlineMember(groupId, userId, cleanNickname, NodeNames.forUser(userId), now),
                    properties.presenceTtlMs());
        }
    }

    public void joinGroup(String groupId, String userId, String nickname) {
        requireGroup(groupId);
        requireUserId(userId);
        String cleanNickname = normalizeNickname(nickname);
        store.upsertPresence(
                new OnlineMember(
                        groupId,
                        userId,
                        cleanNickname,
                        NodeNames.forUser(userId),
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

    public List<ChatMessage> drainOffline(String userId) {
        requireUserId(userId);
        return store.drainOfflineMessages(userId);
    }

    public void disconnect(String userId) {
        store.markOffline(userId);
    }

    public void requireGroup(String groupId) {
        store.ensureGroups();
        if (groupId == null
                || !GROUP_ID.matcher(groupId).matches()
                || !store.groupExists(groupId)) {
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

    private String normalizeContent(String content) {
        String cleaned = content == null ? "" : content.strip();
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("消息内容不能为空");
        }
        if (cleaned.codePointCount(0, cleaned.length()) > properties.maxContentLength()) {
            throw new IllegalArgumentException("消息内容过长");
        }
        return cleaned;
    }

    private String normalizeKind(String kind) {
        return "ascii".equalsIgnoreCase(kind) ? "ascii" : "text";
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
