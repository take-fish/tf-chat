package com.fishmatrix.chat.service;

import com.fishmatrix.chat.model.ChatException;
import com.fishmatrix.chat.model.ChatGroup;
import com.fishmatrix.chat.model.ChatMessage;
import com.fishmatrix.chat.repository.ChatMessageRepository;
import com.fishmatrix.chat.util.ConversationIds;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

class FakeChatMessageRepository implements ChatMessageRepository {

    final List<ChatGroup> groups = new ArrayList<>(List.of(
            new ChatGroup("dev-group", "研发协作", "日常研发"),
            new ChatGroup("ops-oncall", "运维值班", "告警协同"),
            new ChatGroup("tech-share", "技术分享", "方案交流")));
    final List<ChatMessage> messages = new ArrayList<>();
    final Map<String, Long> readState = new HashMap<>();
    private long sequence;

    @Override
    public void ensureGroups() {
        // 内存实现无需初始化
    }

    @Override
    public List<ChatGroup> groups() {
        return List.copyOf(groups);
    }

    @Override
    public boolean groupExists(String groupId) {
        return groups.stream().anyMatch(group -> group.id().equals(groupId));
    }

    @Override
    public SaveResult insertIfAbsent(ChatMessage message) {
        if (message.clientMsgId() != null) {
            ChatMessage existing = messages.stream()
                    .filter(item -> item.fromUserId().equals(message.fromUserId())
                            && Objects.equals(item.clientMsgId(), message.clientMsgId()))
                    .findFirst()
                    .orElse(null);
            if (existing != null) {
                return new SaveResult(existing, false);
            }
        }
        ChatMessage stored = copy(message, ++sequence, message.recalled());
        messages.add(stored);
        return new SaveResult(stored, true);
    }

    @Override
    public HistoryPage groupHistory(String groupId, Long beforeSeq, Long afterSeq, int limit) {
        return page(messages.stream()
                .filter(message -> groupId.equals(message.groupId()))
                .toList(), beforeSeq, afterSeq, limit);
    }

    @Override
    public HistoryPage directHistory(String userA, String userB, Long beforeSeq, Long afterSeq, int limit) {
        return page(messages.stream()
                .filter(message -> message.groupId() == null)
                .filter(message -> (message.fromUserId().equals(userA)
                        && message.toUserId().equals(userB))
                        || (message.fromUserId().equals(userB)
                        && message.toUserId().equals(userA)))
                .toList(), beforeSeq, afterSeq, limit);
    }

    @Override
    public List<ChatMessage> searchGroup(String groupId, String keyword, int limit) {
        return search(messages.stream()
                .filter(message -> groupId.equals(message.groupId()))
                .toList(), keyword, limit);
    }

    @Override
    public List<ChatMessage> searchDirect(String userA, String userB, String keyword, int limit) {
        return search(messages.stream()
                .filter(message -> message.groupId() == null)
                .filter(message -> (message.fromUserId().equals(userA)
                        && message.toUserId().equals(userB))
                        || (message.fromUserId().equals(userB)
                        && message.toUserId().equals(userA)))
                .toList(), keyword, limit);
    }

    @Override
    public ChatMessage recall(String messageId, String userId, long windowMs) {
        ChatMessage existing = messages.stream()
                .filter(message -> message.id().equals(messageId))
                .findFirst()
                .orElseThrow(() -> ChatException.notFound("消息不存在"));
        if (!existing.fromUserId().equals(userId)) {
            throw ChatException.forbidden("只能撤回自己的消息");
        }
        if (existing.recalled()) {
            return existing;
        }
        if (System.currentTimeMillis() - existing.ts() > windowMs) {
            throw ChatException.badRequest("超过撤回时限");
        }
        ChatMessage updated = copy(existing, existing.seq(), true);
        int index = messages.indexOf(existing);
        messages.set(index, updated);
        return updated;
    }

    @Override
    public void markRead(String userId, String groupId, String peerUserId, long seq) {
        String conversationId = groupId != null
                ? ConversationIds.group(groupId)
                : ConversationIds.direct(userId, peerUserId);
        readState.merge(userId + "|" + conversationId, seq, Math::max);
    }

    @Override
    public long latestSeq(String conversationId) {
        return messages.stream()
                .filter(message -> conversationId.equals(conversationOf(message)))
                .mapToLong(ChatMessage::seq)
                .max()
                .orElse(0L);
    }

    @Override
    public UnreadSummary unread(String userId) {
        Map<String, Integer> groupUnread = new LinkedHashMap<>();
        int total = 0;
        for (ChatGroup group : groups) {
            int count = unreadCount(ConversationIds.group(group.id()), userId);
            groupUnread.put(group.id(), count);
            total += count;
        }
        Map<String, Integer> dmUnread = new LinkedHashMap<>();
        for (String conversationId : messages.stream()
                .filter(message -> message.groupId() == null
                        && (message.fromUserId().equals(userId)
                        || message.toUserId().equals(userId)))
                .map(this::conversationOf)
                .distinct()
                .toList()) {
            String peer = ConversationIds.peerOf(conversationId, userId);
            int count = unreadCount(conversationId, userId);
            if (peer != null && count > 0) {
                dmUnread.put(peer, count);
                total += count;
            }
        }
        return new UnreadSummary(groupUnread, dmUnread, total);
    }

    private HistoryPage page(
            List<ChatMessage> candidates,
            Long beforeSeq,
            Long afterSeq,
            int limit) {
        List<ChatMessage> filtered = new ArrayList<>(candidates.stream()
                .filter(message -> afterSeq == null || message.seq() > afterSeq)
                .filter(message -> beforeSeq == null || message.seq() < beforeSeq)
                .sorted(Comparator.comparingLong(ChatMessage::seq))
                .toList());
        if (afterSeq != null) {
            boolean hasMore = filtered.size() > limit;
            List<ChatMessage> page = filtered.subList(0, Math.min(limit, filtered.size()));
            return new HistoryPage(List.copyOf(page), hasMore, null);
        }
        boolean hasMore = filtered.size() > limit;
        List<ChatMessage> page = filtered.subList(
                Math.max(0, filtered.size() - limit), filtered.size());
        Long next = hasMore && !page.isEmpty() ? page.get(0).seq() : null;
        return new HistoryPage(List.copyOf(page), hasMore, next);
    }

    private List<ChatMessage> search(List<ChatMessage> candidates, String keyword, int limit) {
        return candidates.stream()
                .filter(message -> !message.recalled())
                .filter(message -> message.content().contains(keyword))
                .sorted(Comparator.comparingLong(ChatMessage::seq).reversed())
                .limit(limit)
                .toList();
    }

    private int unreadCount(String conversationId, String userId) {
        long read = readState.getOrDefault(userId + "|" + conversationId, 0L);
        return (int) messages.stream()
                .filter(message -> conversationId.equals(conversationOf(message)))
                .filter(message -> message.seq() > read)
                .filter(message -> !message.fromUserId().equals(userId))
                .filter(message -> !message.recalled())
                .count();
    }

    private String conversationOf(ChatMessage message) {
        return message.groupId() != null
                ? ConversationIds.group(message.groupId())
                : ConversationIds.direct(message.fromUserId(), message.toUserId());
    }

    private ChatMessage copy(ChatMessage message, long seq, boolean recalled) {
        return new ChatMessage(
                message.id(),
                seq,
                message.groupId(),
                message.fromUserId(),
                message.fromNickname(),
                message.fromNode(),
                message.toUserId(),
                recalled ? "" : message.content(),
                message.kind(),
                message.ts(),
                message.clientMsgId(),
                recalled);
    }
}
