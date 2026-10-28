package com.fishmatrix.chat.converter;

import com.fishmatrix.chat.domain.MessageScope;
import com.fishmatrix.chat.entity.ChatGroupEntity;
import com.fishmatrix.chat.entity.ChatImageEntity;
import com.fishmatrix.chat.entity.ChatMessageEntity;
import com.fishmatrix.chat.model.ChatGroup;
import com.fishmatrix.chat.model.ChatImage;
import com.fishmatrix.chat.model.ChatMessage;
import com.fishmatrix.chat.util.ConversationIds;
import java.time.Instant;

/**
 * 实体 ⇄ 对外模型的无状态转换。
 *
 * <p>实体只在持久化层内部流转：直接把 entity 交给 controller 就会把
 * conversation_id、recalled_by 之类内部字段序列化出去。</p>
 */
public final class ChatConverters {

    private ChatConverters() {
    }

    public static ChatMessage toMessage(ChatMessageEntity entity) {
        if (entity == null) {
            return null;
        }
        return new ChatMessage(
                entity.getMessageId(),
                entity.getSeq() == null ? 0L : entity.getSeq(),
                entity.getGroupId(),
                entity.getFromUserId(),
                entity.getFromNickname(),
                entity.getFromNode(),
                entity.getToUserId(),
                entity.getContent() == null ? "" : entity.getContent(),
                entity.getKind(),
                epochMillis(entity.getCreatedAt()),
                entity.getClientMsgId(),
                entity.getRecalledAt() != null);
    }

    public static ChatMessageEntity toEntity(ChatMessage message) {
        ChatMessageEntity entity = new ChatMessageEntity();
        entity.setMessageId(message.id());
        entity.setConversationId(conversationIdOf(message));
        entity.setScope(message.groupId() == null ? MessageScope.DM : MessageScope.GROUP);
        entity.setGroupId(message.groupId());
        entity.setFromUserId(message.fromUserId());
        entity.setFromNickname(message.fromNickname());
        entity.setFromNode(message.fromNode());
        entity.setToUserId(message.toUserId());
        entity.setContent(message.content());
        entity.setKind(message.kind());
        entity.setClientMsgId(message.clientMsgId());
        // 写入 message 自带的 ts，保证往返后时间戳不变；非空时自动填充不会覆盖。
        entity.setCreatedAt(Instant.ofEpochMilli(message.ts()));
        return entity;
    }

    public static ChatGroup toGroup(ChatGroupEntity entity) {
        if (entity == null) {
            return null;
        }
        return new ChatGroup(entity.getId(), entity.getName(), entity.getDescription());
    }

    public static ChatImage toImage(ChatImageEntity entity) {
        if (entity == null) {
            return null;
        }
        return new ChatImage(
                entity.getId(),
                entity.getOwnerUserId(),
                entity.getContentType(),
                entity.getSize() == null ? 0L : entity.getSize(),
                epochMillis(entity.getCreatedAt()));
    }

    public static ChatImageEntity toEntity(ChatImage image) {
        ChatImageEntity entity = new ChatImageEntity();
        entity.setId(image.id());
        entity.setOwnerUserId(image.ownerUserId());
        entity.setContentType(image.contentType());
        entity.setSize(image.size());
        entity.setCreatedAt(Instant.ofEpochMilli(image.createdAt()));
        return entity;
    }

    private static String conversationIdOf(ChatMessage message) {
        return message.groupId() != null
                ? ConversationIds.group(message.groupId())
                : ConversationIds.direct(message.fromUserId(), message.toUserId());
    }

    private static long epochMillis(Instant instant) {
        return instant == null ? 0L : instant.toEpochMilli();
    }
}
