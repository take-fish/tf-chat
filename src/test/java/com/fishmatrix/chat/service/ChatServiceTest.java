package com.fishmatrix.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fishmatrix.chat.config.ChatProperties;
import com.fishmatrix.chat.model.ChatException;
import com.fishmatrix.chat.model.ChatGroupView;
import com.fishmatrix.chat.model.ChatIdentity;
import com.fishmatrix.chat.model.ChatMessage;
import com.fishmatrix.chat.model.OnlineMember;
import com.fishmatrix.chat.repository.ChatMessageRepository.HistoryPage;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChatServiceTest {

    private FakeChatStore store;
    private FakeChatMessageRepository repository;
    private ChatImageService images;
    private ChatService service;
    private ChatIdentity alice;
    private ChatIdentity bob;

    @BeforeEach
    void setUp() {
        store = new FakeChatStore();
        repository = new FakeChatMessageRepository();
        images = mock(ChatImageService.class);
        service = new ChatService(store, repository, images, userId -> { }, properties());
        alice = ChatIdentity.of(1, "甲", "USER");
        bob = ChatIdentity.of(2, "乙", "USER");
    }

    @Test
    void groupsIncludeOnlineCountButNoUnread() {
        service.joinGroup(alice, "dev-group");
        service.sendGroupMessage(bob, "dev-group", "现在发布吗", "text", null);

        List<ChatGroupView> groups = service.groups(alice);

        assertThat(groups).extracting(ChatGroupView::id)
                .containsExactly("dev-group", "ops-oncall", "tech-share");
        assertThat(groups.get(0).onlineCount()).isEqualTo(1);
        assertThat(groups).extracting(ChatGroupView::unread).containsOnly(0);
    }

    @Test
    void groupMessagesAreTemporaryAndOnlyPublished() {
        ChatMessage message = service.sendGroupMessage(
                alice, "dev-group", "现在发布吗", "text", "client-1");

        assertThat(message.id()).isNotBlank();
        assertThat(message.seq()).isZero();
        assertThat(message.fromUserId()).isEqualTo("u1");
        assertThat(message.fromNickname()).isEqualTo("甲");
        assertThat(message.fromNode()).startsWith("dev-node-");
        assertThat(repository.messages).isEmpty();
        assertThat(store.events).hasSize(1);
        assertThat(store.events.get(0).type()).isEqualTo("message");
        assertThat(store.events.get(0).target()).isEqualTo("dev-group");
    }

    @Test
    void groupHistoryAndSearchAreEmpty() {
        service.sendGroupMessage(alice, "dev-group", "一", "text", null);
        service.sendGroupMessage(alice, "dev-group", "二", "text", null);

        HistoryPage history = service.groupHistory("dev-group", null, null, 20);

        assertThat(history.messages()).isEmpty();
        assertThat(history.hasMore()).isFalse();
        assertThat(service.groupSearch("dev-group", "一", 10)).isEmpty();
    }

    @Test
    void groupRecallIsNotSupported() {
        ChatMessage message = service.sendGroupMessage(
                alice, "dev-group", "临时消息", "text", null);

        assertThatThrownBy(() -> service.recall(alice, message.id()))
                .isInstanceOf(ChatException.class)
                .hasMessageContaining("不支持撤回");
    }

    @Test
    void groupImageIsValidatedButNotPersisted() {
        String dataUrl = "data:image/png;base64,AAAA";
        when(images.validateGroupDataUrl(dataUrl)).thenReturn(dataUrl);

        ChatMessage message = service.sendGroupMessage(
                alice, "dev-group", dataUrl, "image", "client-image");

        assertThat(message.kind()).isEqualTo(ChatMessage.KIND_IMAGE);
        assertThat(message.content()).isEqualTo(dataUrl);
        assertThat(repository.messages).isEmpty();
        assertThat(store.events).extracting("type").containsExactly("message");
    }

    @Test
    void groupImageContentIsNotTruncatedByTextLimit() {
        String dataUrl = "data:image/png;base64," + "A".repeat(100_000);
        when(images.validateGroupDataUrl(dataUrl)).thenReturn(dataUrl);

        ChatMessage message = service.sendGroupMessage(
                alice, "dev-group", dataUrl, "image", null);

        assertThat(message.content()).isEqualTo(dataUrl);
        assertThat(repository.messages).isEmpty();
    }

    @Test
    void directMessageToOnlineUserIsPersistedAndPublished() {
        store.markOnline(member("u2", "乙"), 60_000);

        ChatMessage message = service.sendDirectMessage(
                alice, "u2", "看下构建", "text", null);

        assertThat(message.toUserId()).isEqualTo("u2");
        assertThat(repository.messages).containsExactly(message);
        assertThat(store.events).extracting("type").containsExactly("dm");
        assertThat(store.offline).isEmpty();
    }

    @Test
    void duplicateDirectMessageIsIdempotent() {
        ChatMessage first = service.sendDirectMessage(
                alice, "u2", "hello", "text", "client-1");
        ChatMessage second = service.sendDirectMessage(
                alice, "u2", "hello", "text", "client-1");

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(repository.messages).hasSize(1);
        assertThat(store.offline.get("u2")).hasSize(1);
    }

    @Test
    void directMessageToOfflineUserIsStoredAndDrained() {
        ChatMessage message = service.sendDirectMessage(
                alice, "u2", "晚点同步", "text", null);

        assertThat(store.offline.get("u2")).containsExactly(message);
        assertThat(service.drainOffline(bob)).containsExactly(message);
        assertThat(store.offline).doesNotContainKey("u2");
    }

    @Test
    void directHistorySupportsCursorPagination() {
        ChatMessage first = service.sendDirectMessage(alice, "u2", "一", "text", null);
        ChatMessage second = service.sendDirectMessage(alice, "u2", "二", "text", null);
        ChatMessage third = service.sendDirectMessage(alice, "u2", "三", "text", null);

        HistoryPage recent = service.directHistory(alice, "u2", null, null, 2);
        assertThat(recent.messages()).extracting(ChatMessage::id)
                .containsExactly(second.id(), third.id());
        assertThat(recent.hasMore()).isTrue();
        assertThat(recent.nextBeforeSeq()).isEqualTo(second.seq());

        HistoryPage older = service.directHistory(
                alice, "u2", recent.nextBeforeSeq(), null, 2);
        assertThat(older.messages()).extracting(ChatMessage::id).containsExactly(first.id());

        HistoryPage missed = service.directHistory(
                alice, "u2", null, first.seq(), 10);
        assertThat(missed.messages()).extracting(ChatMessage::id)
                .containsExactly(second.id(), third.id());
    }

    @Test
    void directSearchAndRecall() {
        ChatMessage message = service.sendDirectMessage(
                alice, "u2", "build passed", "text", null);
        service.sendDirectMessage(bob, "u1", "需求文档", "text", null);

        assertThat(service.directSearch(alice, "u2", "build", 10))
                .extracting(ChatMessage::id)
                .containsExactly(message.id());

        ChatMessage recalled = service.recall(alice, message.id());
        assertThat(recalled.recalled()).isTrue();
        assertThat(recalled.content()).isEmpty();
        assertThat(service.directSearch(alice, "u2", "build", 10)).isEmpty();
        assertThat(store.events).extracting("type").contains("recalled");
        assertThatThrownBy(() -> service.recall(bob, message.id()))
                .isInstanceOf(ChatException.class)
                .hasMessageContaining("只能撤回自己的消息");
    }

    @Test
    void directImageRequiresOwnershipAndIsPersisted() {
        when(images.requireOwned("image-1", "u1")).thenReturn("image-1");

        ChatMessage message = service.sendDirectMessage(
                alice, "u2", "image-1", "image", null);

        assertThat(message.kind()).isEqualTo(ChatMessage.KIND_IMAGE);
        assertThat(message.content()).isEqualTo("image-1");
        assertThat(repository.messages).containsExactly(message);
    }

    @Test
    void directImageRejectsForeignImage() {
        when(images.requireOwned(anyString(), anyString()))
                .thenThrow(ChatException.forbidden("不能发送其他账号上传的图片"));

        assertThatThrownBy(() -> service.sendDirectMessage(
                        alice, "u2", "image-2", "image", null))
                .isInstanceOf(ChatException.class)
                .hasMessageContaining("其他账号");
        assertThat(repository.messages).isEmpty();
    }

    @Test
    void recallWindowIsEnforced() {
        ChatMessage old = service.sendDirectMessage(alice, "u2", "旧消息", "text", null);
        repository.messages.removeIf(message -> message.id().equals(old.id()));
        repository.insertIfAbsent(new ChatMessage(
                old.id(), 1, old.groupId(), old.fromUserId(), old.fromNickname(), old.fromNode(),
                old.toUserId(), old.content(), old.kind(), System.currentTimeMillis() - 300_000,
                null, false));

        assertThatThrownBy(() -> service.recall(alice, old.id()))
                .isInstanceOf(ChatException.class)
                .hasMessageContaining("超过撤回时限");
    }

    @Test
    void directUnreadClearsAfterRead() {
        ChatMessage message = service.sendDirectMessage(bob, "u1", "hi", "text", null);
        assertThat(service.unread(alice).dms().get("u2")).isEqualTo(1);

        service.markRead(alice, null, "u2", message.seq());

        assertThat(service.unread(alice).dms()).doesNotContainKey("u2");
        assertThat(service.unread(alice).total()).isZero();
    }

    @Test
    void groupReadIsNoop() {
        service.sendGroupMessage(bob, "dev-group", "hi", "text", null);

        service.markRead(alice, "dev-group", null, 99L);

        assertThat(service.unread(alice).groups().get("dev-group")).isZero();
    }

    @Test
    void contentControlCharactersAreRemoved() {
        ChatMessage message = service.sendDirectMessage(
                alice, "u2", "[31mred‮text", "text", null);

        assertThat(message.content()).isEqualTo("[31mredtext");
    }

    @Test
    void rateLimitRejectsExcessMessages() {
        ChatService limited = new ChatService(store, repository, images, userId -> {
            throw ChatException.rateLimited("发送过于频繁");
        }, properties());

        assertThatThrownBy(() -> limited.sendGroupMessage(
                        alice, "dev-group", "hi", "text", null))
                .isInstanceOf(ChatException.class)
                .hasMessageContaining("频繁");
        assertThatThrownBy(() -> limited.sendDirectMessage(
                        alice, "u2", "hi", "text", null))
                .isInstanceOf(ChatException.class)
                .hasMessageContaining("频繁");
    }

    @Test
    void heartbeatMaintainsPresence() {
        service.connect(alice);
        service.heartbeat(alice, "dev-group");

        assertThat(service.members("dev-group"))
                .extracting("userId")
                .containsExactly("u1");
        assertThat(service.onlineMembers())
                .extracting("userId")
                .containsExactly("u1");
    }

    @Test
    void rejectsInvalidInput() {
        assertThatThrownBy(() -> service.sendGroupMessage(
                        alice, "dev-group", "   ", "text", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("消息内容");
        assertThatThrownBy(() -> service.groupHistory("missing-group", null, null, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("群组");
        assertThatThrownBy(() -> service.sendDirectMessage(
                        alice, "u1", "hi", "text", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("当前节点");
        assertThatThrownBy(() -> service.groupHistory("dev-group", 10L, 20L, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能同时使用");
    }

    private static OnlineMember member(String userId, String nickname) {
        return new OnlineMember(null, userId, nickname, "dev-node-01", System.currentTimeMillis());
    }

    private static ChatProperties properties() {
        return new ChatProperties(
                false,
                60_000,
                1000,
                500,
                200,
                2000,
                32,
                "http://127.0.0.1:9",
                60,
                60,
                120,
                20,
                List.of("*"),
                "target/chat-images",
                5_242_880,
                1_048_576);
    }
}
