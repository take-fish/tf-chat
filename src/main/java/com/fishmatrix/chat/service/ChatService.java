package com.fishmatrix.chat.service;

import com.fishmatrix.chat.config.ChatProperties;
import com.fishmatrix.chat.converter.ChatConverters;
import com.fishmatrix.chat.entity.ChatGroupEntity;
import com.fishmatrix.chat.entity.ChatMessageEntity;
import com.fishmatrix.chat.error.ChatException;
import com.fishmatrix.chat.mapper.ChatGroupMapper;
import com.fishmatrix.chat.mapper.ChatMessageMapper;
import com.fishmatrix.chat.mapper.ChatReadStateMapper;
import com.fishmatrix.chat.model.ChatEvent;
import com.fishmatrix.chat.model.ChatGroupView;
import com.fishmatrix.chat.model.ChatIdentity;
import com.fishmatrix.chat.model.ChatImage;
import com.fishmatrix.chat.model.ChatMessage;
import com.fishmatrix.chat.model.HistoryPage;
import com.fishmatrix.chat.model.OnlineMember;
import com.fishmatrix.chat.model.SaveResult;
import com.fishmatrix.chat.model.UnreadSummary;
import com.fishmatrix.chat.store.ChatStore;
import com.fishmatrix.chat.util.ConversationIds;
import com.fishmatrix.chat.util.NodeNames;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
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
    private final ChatMessageMapper messageMapper;
    private final ChatGroupMapper groupMapper;
    private final ChatReadStateMapper readStateMapper;
    private final ChatImageService images;
    private final ChatRateLimiter rateLimiter;
    private final ChatProperties properties;

    public List<ChatGroupView> groups(ChatIdentity viewer) {
        Map<String, Integer> unread = unread(viewer).groups();
        return groupMapper.selectAllOrdered().stream()
                .map(group -> new ChatGroupView(
                        group.getId(),
                        group.getName(),
                        group.getDescription(),
                        store.members(group.getId()).size(),
                        unread.getOrDefault(group.getId(), 0)))
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
        return page(
                ConversationIds.direct(viewer.userId(), peerUserId),
                beforeSeq,
                afterSeq,
                clamp(limit, 1, 200));
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
        return search(
                ConversationIds.direct(viewer.userId(), peerUserId),
                requireKeyword(keyword),
                clamp(limit, 1, 100));
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
        SaveResult saved = insertIfAbsent(message);
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
            throw ChatException.badRequest("缺少消息 ID");
        }
        String id = messageId.strip();
        ChatMessage message = findById(id);
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
            messageMapper.markRecalled(id, identity.userId(), Instant.now());
            ChatMessage updated = findById(id);
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
            String conversationId = ConversationIds.direct(viewer.userId(), peerUserId);
            long target = seq == null ? latestSeq(conversationId) : Math.max(0L, seq);
            markRead(viewer.userId(), conversationId, target);
            return;
        }
        throw ChatException.badRequest("缺少群组或私聊对象");
    }

    public UnreadSummary unread(ChatIdentity viewer) {
        return unread(viewer.userId());
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
                && groupMapper.selectById(groupId) != null) {
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
                || groupMapper.selectById(groupId) == null) {
            throw ChatException.badRequest("群组不存在或未授权访问");
        }
    }

    public static void requireUserId(String userId) {
        if (userId == null || !USER_ID.matcher(userId).matches()) {
            throw ChatException.badRequest("userId 格式不合法");
        }
    }

    public String normalizeNickname(String nickname) {
        String cleaned = nickname == null ? "" : nickname.strip().replaceAll("\\s+", " ");
        if (cleaned.isEmpty()) {
            throw ChatException.badRequest("昵称不能为空");
        }
        if (cleaned.codePointCount(0, cleaned.length()) > properties.maxNicknameLength()) {
            throw ChatException.badRequest("昵称过长");
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

    /**
     * 按 (fromUserId, clientMsgId) 幂等写入。唯一索引冲突时回查已有消息，
     * 保证客户端重发不会重复广播。
     */
    private SaveResult insertIfAbsent(ChatMessage message) {
        if (message.clientMsgId() != null) {
            ChatMessageEntity existing =
                    messageMapper.selectByClient(message.fromUserId(), message.clientMsgId());
            if (existing != null) {
                return new SaveResult(ChatConverters.toMessage(existing), false);
            }
        }
        ChatMessageEntity entity = ChatConverters.toEntity(message);
        try {
            messageMapper.insert(entity);
        } catch (DuplicateKeyException exception) {
            ChatMessageEntity existing = message.clientMsgId() == null
                    ? null
                    : messageMapper.selectByClient(message.fromUserId(), message.clientMsgId());
            if (existing != null) {
                return new SaveResult(ChatConverters.toMessage(existing), false);
            }
            throw exception;
        }
        return new SaveResult(ChatConverters.toMessage(entity), true);
    }

    private ChatMessage findById(String messageId) {
        return ChatConverters.toMessage(messageMapper.selectByMessageId(messageId));
    }

    private HistoryPage page(String conversationId, Long beforeSeq, Long afterSeq, int limit) {
        int fetch = limit + 1;
        if (afterSeq != null) {
            List<ChatMessageEntity> rows = messageMapper.selectAfter(conversationId, afterSeq, fetch);
            boolean hasMore = rows.size() > limit;
            List<ChatMessage> messages = rows.subList(0, Math.min(limit, rows.size())).stream()
                    .map(ChatConverters::toMessage).toList();
            return new HistoryPage(messages, hasMore, null);
        }

        List<ChatMessageEntity> rows = messageMapper.selectBefore(conversationId, beforeSeq, fetch);
        boolean hasMore = rows.size() > limit;
        // 查询按 seq 倒序取最近 limit 条，返回给前端时要转回升序。
        List<ChatMessageEntity> ascending =
                new ArrayList<>(rows.subList(0, Math.min(limit, rows.size())));
        Collections.reverse(ascending);
        List<ChatMessage> messages = ascending.stream().map(ChatConverters::toMessage).toList();
        Long nextBeforeSeq = hasMore && !messages.isEmpty() ? messages.get(0).seq() : null;
        return new HistoryPage(messages, hasMore, nextBeforeSeq);
    }

    private List<ChatMessage> search(String conversationId, String keyword, int limit) {
        return messageMapper.selectSearch(conversationId, likePattern(keyword), limit).stream()
                .map(ChatConverters::toMessage)
                .toList();
    }

    private UnreadSummary unread(String userId) {
        // 群聊是临时会话，不累计未读；只统计持久化的私聊。
        Map<String, Integer> groupUnread = new LinkedHashMap<>();
        for (ChatGroupEntity group : groupMapper.selectAllOrdered()) {
            groupUnread.put(group.getId(), 0);
        }
        int total = 0;
        Map<String, Integer> dmUnread = new LinkedHashMap<>();
        for (String conversationId : messageMapper.selectDirectConversations(userId)) {
            String peer = ConversationIds.peerOf(conversationId, userId);
            if (peer == null) {
                continue;
            }
            int count = messageMapper.countUnread(
                    conversationId, lastReadSeq(userId, conversationId), userId);
            if (count > 0) {
                dmUnread.put(peer, count);
                total += count;
            }
        }
        return new UnreadSummary(groupUnread, dmUnread, total);
    }

    /** 位点先更新、缺失再插入；并发插入撞主键时回退为更新。 */
    private void markRead(String userId, String conversationId, long seq) {
        Instant now = Instant.now();
        int updated = readStateMapper.updateLastReadSeq(userId, conversationId, seq, now);
        if (updated > 0) {
            return;
        }
        try {
            readStateMapper.insertReadState(userId, conversationId, seq, now);
        } catch (DuplicateKeyException ignored) {
            readStateMapper.updateLastReadSeq(userId, conversationId, seq, now);
        }
    }

    private long latestSeq(String conversationId) {
        Long seq = messageMapper.selectLatestSeq(conversationId);
        return seq == null ? 0L : seq;
    }

    private long lastReadSeq(String userId, String conversationId) {
        Long seq = readStateMapper.selectLastReadSeq(userId, conversationId);
        return seq == null ? 0L : seq;
    }

    private void requireCursor(Long beforeSeq, Long afterSeq) {
        if (beforeSeq != null && afterSeq != null) {
            throw ChatException.badRequest("beforeSeq 与 afterSeq 不能同时使用");
        }
        if ((beforeSeq != null && beforeSeq < 0) || (afterSeq != null && afterSeq < 0)) {
            throw ChatException.badRequest("游标不合法");
        }
    }

    private void requireNotSelf(ChatIdentity viewer, String peerUserId) {
        if (viewer.userId().equals(peerUserId)) {
            throw ChatException.badRequest("不能与当前节点建立会话");
        }
    }

    private String requireKeyword(String keyword) {
        String cleaned = keyword == null ? "" : keyword.strip();
        if (cleaned.isEmpty()) {
            throw ChatException.badRequest("搜索关键字不能为空");
        }
        return cleaned.length() > 64 ? cleaned.substring(0, 64) : cleaned;
    }

    private String normalizeContent(String content) {
        String cleaned = content == null ? "" : content.replace("\r\n", "\n");
        cleaned = UNSAFE_CONTROL.matcher(cleaned).replaceAll("").strip();
        if (cleaned.isEmpty()) {
            throw ChatException.badRequest("消息内容不能为空");
        }
        if (cleaned.codePointCount(0, cleaned.length()) > properties.maxContentLength()) {
            throw ChatException.badRequest("消息内容过长");
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

    private static String likePattern(String keyword) {
        String escaped = keyword
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
        return "%" + escaped + "%";
    }

    private int clamp(int value, int min, int max) {
        return Math.min(max, Math.max(min, value));
    }
}
