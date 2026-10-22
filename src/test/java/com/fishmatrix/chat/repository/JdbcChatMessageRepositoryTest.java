package com.fishmatrix.chat.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fishmatrix.chat.model.ChatException;
import com.fishmatrix.chat.model.ChatMessage;
import com.fishmatrix.chat.repository.ChatMessageRepository.HistoryPage;
import com.fishmatrix.chat.repository.ChatMessageRepository.SaveResult;
import com.fishmatrix.chat.repository.ChatMessageRepository.UnreadSummary;
import com.fishmatrix.chat.util.ConversationIds;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.jdbc.core.JdbcTemplate;

@JdbcTest
class JdbcChatMessageRepositoryTest {

    @Autowired
    private JdbcTemplate jdbc;

    private JdbcChatMessageRepository repository;

    @BeforeEach
    void setUp() {
        repository = new JdbcChatMessageRepository(jdbc);
    }

    @Test
    void readsDefaultGroupsSeededByMigration() {
        assertThat(repository.groups()).extracting("id")
                .containsExactly("dev-group", "ops-oncall", "tech-share");

        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM chat_group", Integer.class);
        assertThat(count).isEqualTo(3);
        assertThat(repository.groupExists("dev-group")).isTrue();
        assertThat(repository.groupExists("nope")).isFalse();
    }

    @Test
    void paginatesGroupHistoryWithCursors() {
        ChatMessage first = insert(group("m-1", "一"));
        ChatMessage second = insert(group("m-2", "二"));
        ChatMessage third = insert(group("m-3", "三"));

        HistoryPage latest = repository.groupHistory("dev-group", null, null, 2);
        assertThat(latest.messages()).extracting(ChatMessage::id)
                .containsExactly(second.id(), third.id());
        assertThat(latest.hasMore()).isTrue();
        assertThat(latest.nextBeforeSeq()).isEqualTo(second.seq());

        HistoryPage older = repository.groupHistory(
                "dev-group", latest.nextBeforeSeq(), null, 2);
        assertThat(older.messages()).extracting(ChatMessage::id).containsExactly(first.id());
        assertThat(older.hasMore()).isFalse();
        assertThat(older.nextBeforeSeq()).isNull();

        HistoryPage missed = repository.groupHistory(
                "dev-group", null, first.seq(), 10);
        assertThat(missed.messages()).extracting(ChatMessage::id)
                .containsExactly(second.id(), third.id());
        assertThat(missed.hasMore()).isFalse();
    }

    @Test
    void directHistoryIsIsolatedByConversation() {
        ChatMessage outgoing = insert(direct("d-1", "u1", "u2", "hi"));
        ChatMessage incoming = insert(direct("d-2", "u2", "u1", "hello"));
        insert(group("g-1", "群消息"));

        HistoryPage page = repository.directHistory("u2", "u1", null, null, 10);

        assertThat(page.messages()).extracting(ChatMessage::id)
                .containsExactly(outgoing.id(), incoming.id());
        assertThat(repository.directHistory("u1", "u3", null, null, 10).messages()).isEmpty();
        assertThat(repository.groupHistory("dev-group", null, null, 10).messages()).hasSize(1);
    }

    @Test
    void insertIfAbsentDeduplicatesBySenderAndClientId() {
        ChatMessage message = group("m-1", "hello");
        SaveResult first = repository.insertIfAbsent(withClient(message, "client-1"));
        SaveResult second = repository.insertIfAbsent(withClient(
                new ChatMessage(UUID.randomUUID().toString(), 0, message.groupId(),
                        message.fromUserId(), message.fromNickname(), message.fromNode(),
                        null, "hello", "text", System.currentTimeMillis(), null, false),
                "client-1"));

        assertThat(first.created()).isTrue();
        assertThat(first.message().seq()).isPositive();
        assertThat(second.created()).isFalse();
        assertThat(second.message().id()).isEqualTo(first.message().id());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_message", Integer.class))
                .isEqualTo(1);

        SaveResult otherSender = repository.insertIfAbsent(withClient(
                new ChatMessage(UUID.randomUUID().toString(), 0, message.groupId(),
                        "u2", "乙", "dev-node-01", null, "hello", "text",
                        System.currentTimeMillis(), null, false),
                "client-1"));
        assertThat(otherSender.created()).isTrue();
    }

    @Test
    void searchEscapesLikeWildcards() {
        insert(group("m-1", "进度 100% 完成"));
        insert(group("m-2", "进度 1000 完成"));
        insert(group("m-3", "a_b 约定"));
        insert(group("m-4", "axb 约定"));

        assertThat(repository.searchGroup("dev-group", "100%", 10))
                .extracting(ChatMessage::id)
                .containsExactly("m-1");
        assertThat(repository.searchGroup("dev-group", "a_b", 10))
                .extracting(ChatMessage::id)
                .containsExactly("m-3");
    }

    @Test
    void recallChecksOwnerAndWindow() {
        ChatMessage own = insert(direct("m-1", "u1", "u2", "要撤回"));
        ChatMessage recalled = repository.recall(own.id(), "u1", 120_000);

        assertThat(recalled.recalled()).isTrue();
        assertThat(recalled.content()).isEmpty();
        assertThat(repository.searchDirect("u1", "u2", "撤回", 10)).isEmpty();
        assertThat(repository.recall(own.id(), "u1", 120_000).recalled()).isTrue();

        ChatMessage foreign = insert(direct("m-2", "u2", "u1", "别人的"));
        assertThatThrownBy(() -> repository.recall(foreign.id(), "u1", 120_000))
                .isInstanceOf(ChatException.class)
                .hasMessageContaining("只能撤回自己的消息");

        ChatMessage old = insert(new ChatMessage(
                "m-3", 0, null, "u1", "甲", "dev-node-01", "u2",
                "旧消息", "text", System.currentTimeMillis() - 300_000, null, false));
        assertThatThrownBy(() -> repository.recall(old.id(), "u1", 120_000))
                .isInstanceOf(ChatException.class)
                .hasMessageContaining("超过撤回时限");

        assertThatThrownBy(() -> repository.recall("missing", "u1", 120_000))
                .isInstanceOf(ChatException.class)
                .hasMessageContaining("消息不存在");
    }

    @Test
    void markReadUpdatesUnreadCounts() {
        insert(direct("d-1", "u1", "u2", "self"));
        ChatMessage incoming = insert(direct("d-2", "u2", "u1", "from peer"));
        ChatMessage groupMessage = insert(groupFrom("g-1", "u2", "group hello"));

        UnreadSummary before = repository.unread("u1");
        // 群聊是临时会话，不累计未读
        assertThat(before.groups().get("dev-group")).isZero();
        assertThat(before.dms().get("u2")).isEqualTo(1);
        assertThat(before.total()).isEqualTo(1);

        repository.markRead("u1", "dev-group", null, groupMessage.seq());
        repository.markRead("u1", null, "u2", incoming.seq());
        repository.markRead("u1", "dev-group", null, groupMessage.seq());

        UnreadSummary after = repository.unread("u1");
        assertThat(after.groups().get("dev-group")).isZero();
        assertThat(after.dms()).isEmpty();
        assertThat(after.total()).isZero();
        assertThat(repository.latestSeq(ConversationIds.group("dev-group")))
                .isEqualTo(groupMessage.seq());
    }

    private ChatMessage insert(ChatMessage message) {
        return repository.insertIfAbsent(message).message();
    }

    private static ChatMessage group(String id, String content) {
        return groupFrom(id, "u1", content);
    }

    private static ChatMessage groupFrom(String id, String from, String content) {
        return new ChatMessage(
                id, 0, "dev-group", from, from.equals("u1") ? "甲" : "乙",
                "dev-node-01", null, content, "text", System.currentTimeMillis(), null, false);
    }

    private static ChatMessage direct(String id, String from, String to, String content) {
        return new ChatMessage(
                id, 0, null, from, from.equals("u1") ? "甲" : "乙", "dev-node-01",
                to, content, "text", System.currentTimeMillis(), null, false);
    }

    private static ChatMessage withClient(ChatMessage message, String clientMsgId) {
        return new ChatMessage(
                message.id(), message.seq(), message.groupId(), message.fromUserId(),
                message.fromNickname(), message.fromNode(), message.toUserId(),
                message.content(), message.kind(), message.ts(), clientMsgId,
                message.recalled());
    }
}
