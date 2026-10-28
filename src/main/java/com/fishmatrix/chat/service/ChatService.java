package com.fishmatrix.chat.service;

import com.fishmatrix.chat.config.ChatProperties;
import com.fishmatrix.chat.model.ChatEvent;
import com.fishmatrix.chat.model.ChatException;
import com.fishmatrix.chat.model.ChatGroupView;
import com.fishmatrix.chat.model.ChatIdentity;
import com.fishmatrix.chat.model.ChatImage;
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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class ChatService {

    private static final Pattern USER_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern GROUP_ID = Pattern.compile("[a-z0-9-]{1,32}");
    private static final Pattern UNSAFE_CONTROL = Pattern.compile(
            "[\\u0000-\\u0008\\u000B-\\u001F\\u007F-\\u009F\\u202A-\\u202E\\u2066-\\u2069]");

    private final ChatStore store;
    private final ChatMessageRepository messages;
    private final ChatImageService images;
    private final ChatRateLimiter rateLimiter;
    private final ChatProperties properties;

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
        // 群聊是临时会话：消息只在在线连接间实时转发，不提供历史。
        return new HistoryPage(List.of(), false, null);
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
        requireKeyword(keyword);
        // 群聊不做持久化，无法回看或搜索。
        return List.of();
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
        String normalizedKind = normalizeKind(kind);
        String normalizedContent = ChatMessage.KIND_IMAGE.equals(normalizedKind)
                ? images.validateGroupDataUrl(content)
                : normalizeContent(content);
        ChatMessage message = newMessage(
                sender, groupId, null, normalizedContent, normalizedKind, clientMsgId);
        // 群聊为临时会话：不写 MySQL，只广播给当前在线的群成员。
        store.publish(ChatEvent.groupMessage(message));
        return message;
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
        String normalizedKind = normalizeKind(kind);
        String normalizedContent = ChatMessage.KIND_IMAGE.equals(normalizedKind)
                ? images.requireOwned(content, sender.userId())
                : normalizeContent(content);
        ChatMessage message = newMessage(
                sender, null, toUserId, normalizedContent, normalizedKind, clientMsgId);
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
        String id = messageId.strip();
        ChatMessage message = messages.findById(id);
        if (message == null) {
            throw ChatException.notFound("消息不存在或群聊消息不支持撤回");
        }
        if (message.groupId() != null) {
            throw ChatException.badRequest("群聊为临时会话，不支持撤回");
        }
        if (!message.fromUserId().equals(identity.userId())) {
            throw ChatException.forbidden("只能撤回自己的消息");
        }
        if (!message.recalled()) {
            if (System.currentTimeMillis() - message.ts()
                    > properties.recallWindowSeconds() * 1000L) {
                throw ChatException.badRequest("超过撤回时限");
            }
            messages.markRecalled(id, identity.userId(), System.currentTimeMillis());
            ChatMessage updated = messages.findById(id);
            if (updated != null) {
                message = updated;
            }
        }
        store.publish(ChatEvent.recalled(message.fromUserId(), message));
        if (message.toUserId() != null) {
            store.publish(ChatEvent.recalled(message.toUserId(), message));
        }
        return message;
    }

    public void markRead(ChatIdentity viewer, String groupId, String peerUserId, Long seq) {
        if (groupId != null) {
            requireGroup(groupId);
            // 群聊不保存历史，也没有跨会话未读，无需记录已读位点。
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

    public ChatImage uploadImage(ChatIdentity viewer, MultipartFile file) {
        return images.upload(viewer, file);
    }

    public ChatImageService.StoredImage loadImage(ChatIdentity viewer, String imageId) {
        return images.load(viewer, imageId);
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
        String normalizedKind = normalizeKind(kind);
        // 图片内容可能是 data URL 或图片 ID，已由 ChatImageService 校验，不能再按文本长度清理。
        String normalizedContent = ChatMessage.KIND_IMAGE.equals(normalizedKind)
                ? content
                : normalizeContent(content);
        return new ChatMessage(
                UUID.randomUUID().toString(),
                0L,
                groupId,
                sender.userId(),
                normalizeNickname(sender.nickname()),
                NodeNames.forUser(sender.userId()),
                toUserId,
                normalizedContent,
                normalizedKind,
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
        if (ChatMessage.KIND_ASCII.equalsIgnoreCase(kind)) {
            return ChatMessage.KIND_ASCII;
        }
        if (ChatMessage.KIND_IMAGE.equalsIgnoreCase(kind)) {
            return ChatMessage.KIND_IMAGE;
        }
        return ChatMessage.KIND_TEXT;
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
