package com.fishmatrix.chat.repository;

import com.fishmatrix.chat.model.ChatException;
import com.fishmatrix.chat.model.ChatGroup;
import com.fishmatrix.chat.model.ChatMessage;
import com.fishmatrix.chat.util.ConversationIds;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcChatMessageRepository implements ChatMessageRepository {

    private static final String SELECT_MESSAGE =
            "SELECT seq, message_id, group_id, from_user_id, from_nickname, from_node, "
                    + "to_user_id, content, kind, created_at, client_msg_id, recalled_at "
                    + "FROM chat_message";

    private static final RowMapper<ChatMessage> MESSAGE_MAPPER = (rs, index) -> new ChatMessage(
            rs.getString("message_id"),
            rs.getLong("seq"),
            rs.getString("group_id"),
            rs.getString("from_user_id"),
            rs.getString("from_nickname"),
            rs.getString("from_node"),
            rs.getString("to_user_id"),
            rs.getString("content"),
            rs.getString("kind"),
            rs.getTimestamp("created_at").getTime(),
            rs.getString("client_msg_id"),
            rs.getTimestamp("recalled_at") != null);

    private final JdbcTemplate jdbc;

    public JdbcChatMessageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ChatGroup> groups() {
        return jdbc.query(
                "SELECT id, name, description FROM chat_group ORDER BY sort_order, id",
                (rs, index) -> new ChatGroup(
                        rs.getString("id"), rs.getString("name"), rs.getString("description")));
    }

    @Override
    public boolean groupExists(String groupId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM chat_group WHERE id = ?", Integer.class, groupId);
        return count != null && count > 0;
    }

    @Override
    public SaveResult insertIfAbsent(ChatMessage message) {
        if (message.clientMsgId() != null) {
            ChatMessage existing = findByClient(message.fromUserId(), message.clientMsgId());
            if (existing != null) {
                return new SaveResult(existing, false);
            }
        }
        String conversationId = conversationIdOf(message);
        Timestamp createdAt = new Timestamp(message.ts());
        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO chat_message "
                                + "(message_id, conversation_id, scope, group_id, from_user_id, "
                                + "from_nickname, from_node, to_user_id, content, kind, client_msg_id, created_at) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS);
                statement.setString(1, message.id());
                statement.setString(2, conversationId);
                statement.setString(3, message.groupId() == null ? "DM" : "GROUP");
                statement.setString(4, message.groupId());
                statement.setString(5, message.fromUserId());
                statement.setString(6, message.fromNickname());
                statement.setString(7, message.fromNode());
                statement.setString(8, message.toUserId());
                statement.setString(9, message.content());
                statement.setString(10, message.kind());
                statement.setString(11, message.clientMsgId());
                statement.setTimestamp(12, createdAt);
                return statement;
            }, keyHolder);
        } catch (DuplicateKeyException exception) {
            ChatMessage existing = message.clientMsgId() == null
                    ? null
                    : findByClient(message.fromUserId(), message.clientMsgId());
            if (existing != null) {
                return new SaveResult(existing, false);
            }
            throw exception;
        }
        Number key = keyHolder.getKey();
        long seq = key == null ? 0L : key.longValue();
        ChatMessage stored = new ChatMessage(
                message.id(),
                seq,
                message.groupId(),
                message.fromUserId(),
                message.fromNickname(),
                message.fromNode(),
                message.toUserId(),
                message.content(),
                message.kind(),
                message.ts(),
                message.clientMsgId(),
                false);
        return new SaveResult(stored, true);
    }

    @Override
    public HistoryPage groupHistory(String groupId, Long beforeSeq, Long afterSeq, int limit) {
        return page(ConversationIds.group(groupId), beforeSeq, afterSeq, limit);
    }

    @Override
    public HistoryPage directHistory(String userA, String userB, Long beforeSeq, Long afterSeq, int limit) {
        return page(ConversationIds.direct(userA, userB), beforeSeq, afterSeq, limit);
    }

    @Override
    public List<ChatMessage> searchGroup(String groupId, String keyword, int limit) {
        return search(ConversationIds.group(groupId), keyword, limit);
    }

    @Override
    public List<ChatMessage> searchDirect(String userA, String userB, String keyword, int limit) {
        return search(ConversationIds.direct(userA, userB), keyword, limit);
    }

    @Override
    public ChatMessage recall(String messageId, String userId, long windowMs) {
        ChatMessage existing = findById(messageId);
        if (existing == null) {
            throw ChatException.notFound("消息不存在或群聊消息不支持撤回");
        }
        if (existing.groupId() != null) {
            throw ChatException.badRequest("群聊为临时会话，不支持撤回");
        }
        if (!existing.fromUserId().equals(userId)) {
            throw ChatException.forbidden("只能撤回自己的消息");
        }
        if (existing.recalled()) {
            return existing;
        }
        if (System.currentTimeMillis() - existing.ts() > windowMs) {
            throw ChatException.badRequest("超过撤回时限");
        }
        jdbc.update(
                "UPDATE chat_message SET content = '', recalled_at = ?, recalled_by = ? "
                        + "WHERE message_id = ? AND recalled_at IS NULL",
                new Timestamp(System.currentTimeMillis()),
                userId,
                messageId);
        ChatMessage updated = findById(messageId);
        return updated == null ? existing : updated;
    }

    @Override
    public void markRead(String userId, String groupId, String peerUserId, long seq) {
        String conversationId = groupId != null
                ? ConversationIds.group(groupId)
                : ConversationIds.direct(userId, peerUserId);
        long target = Math.max(0L, seq);
        Timestamp now = new Timestamp(System.currentTimeMillis());
        int updated = jdbc.update(
                "UPDATE chat_read_state SET last_read_seq = GREATEST(last_read_seq, ?), updated_at = ? "
                        + "WHERE user_id = ? AND conversation_id = ?",
                target, now, userId, conversationId);
        if (updated > 0) {
            return;
        }
        try {
            jdbc.update(
                    "INSERT INTO chat_read_state (user_id, conversation_id, last_read_seq, updated_at) "
                            + "VALUES (?, ?, ?, ?)",
                    userId, conversationId, target, now);
        } catch (DuplicateKeyException ignored) {
            jdbc.update(
                    "UPDATE chat_read_state SET last_read_seq = GREATEST(last_read_seq, ?), updated_at = ? "
                            + "WHERE user_id = ? AND conversation_id = ?",
                    target, now, userId, conversationId);
        }
    }

    @Override
    public long latestSeq(String conversationId) {
        Long seq = jdbc.queryForObject(
                "SELECT COALESCE(MAX(seq), 0) FROM chat_message WHERE conversation_id = ?",
                Long.class,
                conversationId);
        return seq == null ? 0L : seq;
    }

    @Override
    public UnreadSummary unread(String userId) {
        // 群聊是临时会话，不累计未读；只统计持久化的私聊。
        Map<String, Integer> groupUnread = new LinkedHashMap<>();
        for (ChatGroup group : groups()) {
            groupUnread.put(group.id(), 0);
        }
        int total = 0;

        Map<String, Integer> dmUnread = new LinkedHashMap<>();
        List<String> conversations = jdbc.queryForList(
                "SELECT DISTINCT conversation_id FROM chat_message "
                        + "WHERE scope = 'DM' AND (from_user_id = ? OR to_user_id = ?)",
                String.class,
                userId,
                userId);
        for (String conversationId : conversations) {
            String peer = ConversationIds.peerOf(conversationId, userId);
            if (peer == null) {
                continue;
            }
            int count = unreadCount(conversationId, userId);
            if (count > 0) {
                dmUnread.put(peer, count);
                total += count;
            }
        }
        return new UnreadSummary(groupUnread, dmUnread, total);
    }

    private HistoryPage page(String conversationId, Long beforeSeq, Long afterSeq, int limit) {
        int fetch = limit + 1;
        if (afterSeq != null) {
            List<ChatMessage> rows = jdbc.query(
                    SELECT_MESSAGE + " WHERE conversation_id = ? AND seq > ? ORDER BY seq ASC LIMIT ?",
                    MESSAGE_MAPPER,
                    conversationId,
                    afterSeq,
                    fetch);
            boolean hasMore = rows.size() > limit;
            List<ChatMessage> page = hasMore ? rows.subList(0, limit) : rows;
            return new HistoryPage(List.copyOf(page), hasMore, null);
        }

        StringBuilder sql = new StringBuilder(SELECT_MESSAGE).append(" WHERE conversation_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(conversationId);
        if (beforeSeq != null) {
            sql.append(" AND seq < ?");
            args.add(beforeSeq);
        }
        sql.append(" ORDER BY seq DESC LIMIT ?");
        args.add(fetch);

        List<ChatMessage> rows = jdbc.query(sql.toString(), MESSAGE_MAPPER, args.toArray());
        boolean hasMore = rows.size() > limit;
        List<ChatMessage> page = new ArrayList<>(
                rows.subList(0, Math.min(limit, rows.size())));
        Collections.reverse(page);
        Long nextBeforeSeq = hasMore && !page.isEmpty() ? page.get(0).seq() : null;
        return new HistoryPage(List.copyOf(page), hasMore, nextBeforeSeq);
    }

    private List<ChatMessage> search(String conversationId, String keyword, int limit) {
        return jdbc.query(
                SELECT_MESSAGE + " WHERE conversation_id = ? AND recalled_at IS NULL "
                        + "AND content LIKE ? ESCAPE '!' ORDER BY seq DESC LIMIT ?",
                MESSAGE_MAPPER,
                conversationId,
                likePattern(keyword),
                limit);
    }

    private int unreadCount(String conversationId, String userId) {
        long readSeq = readSeq(userId, conversationId);
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM chat_message WHERE conversation_id = ? AND seq > ? "
                        + "AND from_user_id <> ? AND recalled_at IS NULL",
                Integer.class,
                conversationId,
                readSeq,
                userId);
        return count == null ? 0 : count;
    }

    private long readSeq(String userId, String conversationId) {
        List<Long> values = jdbc.queryForList(
                "SELECT last_read_seq FROM chat_read_state WHERE user_id = ? AND conversation_id = ?",
                Long.class,
                userId,
                conversationId);
        return values.isEmpty() || values.get(0) == null ? 0L : values.get(0);
    }

    private ChatMessage findById(String messageId) {
        List<ChatMessage> messages = jdbc.query(
                SELECT_MESSAGE + " WHERE message_id = ?", MESSAGE_MAPPER, messageId);
        return messages.isEmpty() ? null : messages.get(0);
    }

    private ChatMessage findByClient(String fromUserId, String clientMsgId) {
        List<ChatMessage> messages = jdbc.query(
                SELECT_MESSAGE + " WHERE from_user_id = ? AND client_msg_id = ?",
                MESSAGE_MAPPER,
                fromUserId,
                clientMsgId);
        return messages.isEmpty() ? null : messages.get(0);
    }

    private String conversationIdOf(ChatMessage message) {
        return message.groupId() != null
                ? ConversationIds.group(message.groupId())
                : ConversationIds.direct(message.fromUserId(), message.toUserId());
    }

    private String likePattern(String keyword) {
        String escaped = keyword
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
        return "%" + escaped + "%";
    }
}
