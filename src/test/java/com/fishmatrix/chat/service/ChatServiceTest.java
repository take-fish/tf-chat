package com.fishmatrix.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fishmatrix.chat.config.ChatProperties;
import com.fishmatrix.chat.model.ChatGroupView;
import com.fishmatrix.chat.model.ChatMessage;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChatServiceTest {

    private FakeChatStore store;
    private ChatService service;

    @BeforeEach
    void setUp() {
        store = new FakeChatStore();
        ChatProperties properties = new ChatProperties(true, 60_000, 1000, 500, 200, 2000, 32);
        service = new ChatService(store, properties);
    }

    @Test
    void groupsIncludeOnlineCount() {
        service.joinGroup("dev-group", "user-a", "甲");

        List<ChatGroupView> groups = service.groups();

        assertThat(groups).extracting(ChatGroupView::id)
                .containsExactly("dev-group", "ops-oncall", "tech-share");
        assertThat(groups.get(0).onlineCount()).isEqualTo(1);
        assertThat(groups.get(1).onlineCount()).isZero();
    }

    @Test
    void groupMessageAppendsHistoryAndPublishesEvent() {
        ChatMessage message = service.sendGroupMessage(
                "dev-group", "user-a", "甲", "现在发布吗", "text", "client-1");

        assertThat(message.id()).isNotBlank();
        assertThat(message.fromNode()).isEqualTo("dev-node-62");
        assertThat(store.groupHistories.get("dev-group")).hasSize(1);
        assertThat(store.events).hasSize(1);
        assertThat(store.events.get(0).type()).isEqualTo("message");
        assertThat(store.events.get(0).target()).isEqualTo("dev-group");
        assertThat(store.events.get(0).message().content()).isEqualTo("现在发布吗");
    }

    @Test
    void directMessageToOnlineUserIsPublished() {
        store.online.add("user-b");

        ChatMessage message = service.sendDirectMessage(
                "user-b", "user-a", "甲", "看下构建", "text", null);

        assertThat(message.toUserId()).isEqualTo("user-b");
        assertThat(store.events).extracting("type").containsExactly("dm");
        assertThat(store.offline).isEmpty();
    }

    @Test
    void directMessageToOfflineUserIsStoredAndDrained() {
        ChatMessage message = service.sendDirectMessage(
                "user-b", "user-a", "甲", "晚点同步", "text", null);

        assertThat(store.offline.get("user-b")).containsExactly(message);
        assertThat(service.drainOffline("user-b")).containsExactly(message);
        assertThat(store.offline).doesNotContainKey("user-b");
    }

    @Test
    void heartbeatMaintainsPresence() {
        service.connect("user-a", "甲");
        service.heartbeat("user-a", "甲", "dev-group");

        assertThat(service.members("dev-group"))
                .extracting("userId")
                .containsExactly("user-a");
    }

    @Test
    void rejectsInvalidInput() {
        assertThatThrownBy(() -> service.sendGroupMessage(
                        "dev-group", "user-a", "甲", "   ", "text", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("消息内容");
        assertThatThrownBy(() -> service.groupHistory("missing-group", 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("群组");
        assertThatThrownBy(() -> service.sendDirectMessage(
                        "user-a", "user-a", "甲", "hi", "text", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("当前节点");
    }
}
