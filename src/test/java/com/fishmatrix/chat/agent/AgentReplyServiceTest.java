package com.fishmatrix.chat.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fishmatrix.chat.config.ChatAgentProperties;
import com.fishmatrix.chat.config.ChatProperties;
import com.fishmatrix.chat.entity.ChatMessageEntity;
import com.fishmatrix.chat.mapper.ChatMessageMapper;
import com.fishmatrix.chat.model.ChatEvent;
import com.fishmatrix.chat.model.ChatIdentity;
import com.fishmatrix.chat.model.ChatMessage;
import com.fishmatrix.chat.model.OnlineMember;
import com.fishmatrix.chat.service.ChatImageService;
import com.fishmatrix.chat.store.ChatStore;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AgentReplyServiceTest {

    private final RecordingStore store = new RecordingStore();
    private final ChatMessageMapper messageMapper = mock(ChatMessageMapper.class);
    private final ChatImageService images = mock(ChatImageService.class);
    private final StubClient client = new StubClient();
    private AgentImageCache imageCache;
    private final ChatIdentity alice = ChatIdentity.of(1, "甲", "USER", "alice",
            List.of("USER"), List.of("agent.chat.use"));

    @AfterEach
    void tearDown() {
        client.behavior = listener -> {
        };
    }

    private AgentReplyService service(ChatAgentProperties properties) {
        AgentProfileCache profile = new AgentProfileCache(properties, client);
        imageCache = new AgentImageCache(properties);
        return new AgentReplyService(
                properties,
                chatProperties(),
                profile,
                client,
                store,
                messageMapper,
                imageCache,
                images);
    }

    private static ChatProperties chatProperties() {
        return new ChatProperties(false, 60_000L, 1000, 500, 200, 2000, 32, 60, 120, 20,
                List.of("*"), "target/chat-images", 1024, 1024);
    }

    private static ChatAgentProperties properties(boolean enabled, String fallback, int concurrency) {
        return new ChatAgentProperties(
                enabled, "http://127.0.0.1:1", "prod", fallback, 60, 1, 5, concurrency, 4,
                1, 4000, 5, 4000, 2000, 120, 16);
    }

    @Test
    @DisplayName("群里 @ 一下，回答按片段广播给同一个群")
    void streamsChunksToGroup() {
        client.behavior = listener -> {
            listener.onDelta("结论：");
            listener.onDelta("dev-group 最近 30 分钟没有 5xx。");
            listener.onComplete("结论：dev-group 最近 30 分钟没有 5xx。");
        };
        AgentReplyService service = service(properties(true, "小鱼", 1));

        service.afterGroupMessage(alice, "dev-group", "@小鱼 最近有报错吗");

        awaitIdle(service);
        List<ChatMessage> chunks = messagesOfType("message");
        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.groupId()).isEqualTo("dev-group");
            assertThat(chunk.fromUserId()).isEqualTo(AgentReplyService.BOT_USER_ID);
            assertThat(chunk.fromNickname()).isEqualTo("小鱼");
            assertThat(chunk.streamId()).isNotBlank();
        });
        assertThat(chunks).extracting(ChatMessage::streamId).doesNotContainNull().containsOnly(
                chunks.get(0).streamId());
        assertThat(String.join("", chunks.stream().map(ChatMessage::content).toList()))
                .isEqualTo("结论：dev-group 最近 30 分钟没有 5xx。");
        assertThat(client.prompts).containsExactly("最近有报错吗");
        verifyNoInteractions(messageMapper);
    }

    @Test
    @DisplayName("私聊里的回答发给双方，并只在结束时落一条完整消息")
    void streamsToBothSidesAndPersistsOnce() {
        client.behavior = listener -> {
            listener.onDelta("今天没有");
            listener.onDelta("发布窗口。");
            listener.onComplete("今天没有发布窗口。");
        };
        AgentReplyService service = service(properties(true, "小鱼", 1));

        service.afterDirectMessage(alice, "u2", "@小鱼 今天发布吗");

        awaitIdle(service);
        assertThat(store.events).extracting(ChatEvent::target)
                .contains("u1", "u2")
                .doesNotContain("agent");
        assertThat(messagesOfType("dm")).isNotEmpty();
        // 双方各收一份，toUserId 指向各自的会话对端，前端才能认出这是当前私聊
        assertThat(store.events.stream()
                        .filter(event -> "dm".equals(event.type()))
                        .map(event -> event.target() + ":" + event.message().toUserId()))
                .contains("u1:u2", "u2:u1");

        ArgumentCaptor<ChatMessageEntity> captor = ArgumentCaptor.forClass(ChatMessageEntity.class);
        verify(messageMapper).insert(captor.capture());
        ChatMessageEntity saved = captor.getValue();
        assertThat(saved.getConversationId()).isEqualTo("dm:u1:u2");
        assertThat(saved.getFromUserId()).isEqualTo(AgentReplyService.BOT_USER_ID);
        assertThat(saved.getContent()).isEqualTo("今天没有发布窗口。");
        assertThat(saved.getToUserId()).isEqualTo("u2");
    }

    @Test
    @DisplayName("同一会话上一轮还没答完时，再 @ 只回一句提示，不再调模型")
    void rejectsConcurrentTurnsInSameConversation() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        client.behavior = listener -> {
            started.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            listener.onDelta("答完了");
            listener.onComplete("答完了");
        };
        AgentReplyService service = service(properties(true, "小鱼", 1));

        service.afterGroupMessage(alice, "dev-group", "@小鱼 第一个问题");
        assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
        service.afterGroupMessage(alice, "dev-group", "@小鱼 第二个问题");
        release.countDown();

        awaitIdle(service);
        assertThat(client.prompts).containsExactly("第一个问题");
        assertThat(messagesOfType("message")).extracting(ChatMessage::content)
                .anySatisfy(text -> assertThat(text).contains("我正在回答上一条"));
    }

    @Test
    @DisplayName("没 @ 到名字、或功能关掉时，一条消息都不发")
    void staysSilentWhenNotTriggered() {
        AgentReplyService service = service(properties(true, "小鱼", 1));

        service.afterGroupMessage(alice, "dev-group", "普通聊天，不叫助手");
        service.afterDirectMessage(alice, "u2", "私聊也不叫");

        assertThat(store.events).isEmpty();
        assertThat(client.prompts).isEmpty();
    }

    @Test
    @DisplayName("开关关掉时 @ 也不触发")
    void disabledSendsNothing() {
        AgentReplyService service = service(properties(false, "小鱼", 1));

        service.afterGroupMessage(alice, "dev-group", "@小鱼 帮我看下");

        assertThat(store.events).isEmpty();
        assertThat(client.prompts).isEmpty();
    }

    @Test
    @DisplayName("agent 报错时把原因说清楚，不刷一堆空片段")
    void reportsAgentErrorAsSingleMessage() {
        client.behavior = listener -> listener.onError("智能助手正忙，请稍后再试");
        AgentReplyService service = service(properties(true, "小鱼", 1));

        service.afterGroupMessage(alice, "dev-group", "@小鱼 查日志");

        awaitIdle(service);
        assertThat(messagesOfType("message")).hasSize(1);
        assertThat(messagesOfType("message").get(0).content())
                .contains("智能助手暂时不可用")
                .contains("正忙");
    }

    @Test
    @DisplayName("回答为空时给一句可操作的兜底，而不是静默")
    void emptyAnswerGetsFallbackText() {
        client.behavior = listener -> listener.onComplete("");
        AgentReplyService service = service(properties(true, "小鱼", 1));

        service.afterGroupMessage(alice, "dev-group", "@小鱼 查不到的东西");

        awaitIdle(service);
        assertThat(messagesOfType("message")).extracting(ChatMessage::content)
                .anySatisfy(text -> assertThat(text).contains("没查到结果"));
    }

    @Test
    @DisplayName("群聊先发图、再 @：把最近一张图随提问发给 agent")
    void attachesRecentGroupImageToMention() {
        String dataUrl = "data:image/png;base64,AQID";
        byte[] bytes = {1, 2, 3};
        when(images.readGroupDataUrl(dataUrl))
                .thenReturn(new ChatImageService.StoredImage(bytes, "image/png"));
        client.behavior = listener -> listener.onComplete("图里是错误日志");
        AgentReplyService service = service(properties(true, "小鱼", 1));

        imageCache.rememberGroup("group:dev-group", "u1", dataUrl);
        service.afterGroupMessage(alice, "dev-group", "@小鱼 这个报错什么意思");

        awaitIdle(service);
        assertThat(client.images).hasSize(1);
        AgentImage image = client.images.get(0);
        assertThat(image).isNotNull();
        assertThat(image.mime()).isEqualTo("image/png");
        assertThat(image.bytes()).isEqualTo(3);
        assertThat(image.base64()).isEqualTo(Base64.getEncoder().encodeToString(bytes));
    }

    @Test
    @DisplayName("私聊先发图、再 @：按 imageId 读取并保留 owner 校验")
    void attachesRecentDirectImageToMention() {
        byte[] bytes = {9, 8, 7};
        when(images.load(alice, "img-1"))
                .thenReturn(new ChatImageService.StoredImage(bytes, "image/jpeg"));
        client.behavior = listener -> listener.onComplete("看到了");
        AgentReplyService service = service(properties(true, "小鱼", 1));

        imageCache.rememberDirect("dm:u1:u2", "u1", "img-1");
        service.afterDirectMessage(alice, "u2", "@小鱼 图里写了什么");

        awaitIdle(service);
        assertThat(client.images).hasSize(1);
        assertThat(client.images.get(0).mime()).isEqualTo("image/jpeg");
        assertThat(client.images.get(0).base64()).isEqualTo(Base64.getEncoder().encodeToString(bytes));
    }

    @Test
    @DisplayName("别人刚发的图不会挂到我的提问上")
    void doesNotAttachAnotherUsersImage() {
        client.behavior = listener -> listener.onComplete("没有图也能答");
        AgentReplyService service = service(properties(true, "小鱼", 1));

        imageCache.rememberGroup("group:dev-group", "u2", "data:image/png;base64,AQID");
        service.afterGroupMessage(alice, "dev-group", "@小鱼 这是什么");

        awaitIdle(service);
        assertThat(client.images).hasSize(1);
        assertThat(client.images.get(0)).isNull();
    }

    @Test
    @DisplayName("图片过期或读取失败时按纯文本继续，不阻断回答")
    void fallsBackToTextWhenImageCannotBeRead() {
        String dataUrl = "data:image/png;base64,AQID";
        when(images.readGroupDataUrl(dataUrl))
                .thenThrow(new IllegalStateException("文件不存在"));
        client.behavior = listener -> listener.onComplete("按文字回答");
        AgentReplyService service = service(properties(true, "小鱼", 1));

        imageCache.rememberGroup("group:dev-group", "u1", dataUrl);
        service.afterGroupMessage(alice, "dev-group", "@小鱼 这是什么");

        awaitIdle(service);
        assertThat(client.images).hasSize(1);
        assertThat(client.images.get(0)).isNull();
        assertThat(String.join("", messagesOfType("message").stream()
                .map(ChatMessage::content).toList())).isEqualTo("按文字回答");
    }

    private List<ChatMessage> messagesOfType(String type) {
        return store.events.stream()
                .filter(event -> type.equals(event.type()))
                .map(ChatEvent::message)
                .filter(message -> message != null
                        && AgentReplyService.BOT_USER_ID.equals(message.fromUserId()))
                .toList();
    }

    private void awaitIdle(AgentReplyService service) {
        long deadline = System.currentTimeMillis() + 5000;
        while (!service.activeConversations().isEmpty()
                && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
        }
        assertThat(service.activeConversations()).isEmpty();
    }

    private static final class StubClient implements AgentApiClient {

        final List<String> prompts = new ArrayList<>();
        final List<AgentImage> images = new ArrayList<>();
        volatile Consumer<AgentStreamListener> behavior = listener -> {
        };

        @Override
        public Optional<String> displayName() {
            return Optional.of("小鱼");
        }

        @Override
        public void stream(AgentCaller caller, String prompt, String sessionId,
                AgentStreamListener listener) {
            stream(caller, prompt, sessionId, null, listener);
        }

        @Override
        public void stream(AgentCaller caller, String prompt, String sessionId,
                AgentImage image, AgentStreamListener listener) {
            prompts.add(prompt);
            images.add(image);
            behavior.accept(listener);
        }
    }

    private static final class RecordingStore implements ChatStore {

        final List<ChatEvent> events = new ArrayList<>();
        final AtomicReference<String> lastUserId = new AtomicReference<>();

        @Override
        public void addOfflineMessage(String userId, ChatMessage message) {
            lastUserId.set(userId);
        }

        @Override
        public List<ChatMessage> drainOfflineMessages(String userId) {
            return List.of();
        }

        @Override
        public void markOnline(OnlineMember member, long ttlMs) {
        }

        @Override
        public boolean isOnline(String userId) {
            return true;
        }

        @Override
        public void markOffline(String userId) {
        }

        @Override
        public void upsertPresence(OnlineMember member, long ttlMs) {
        }

        @Override
        public void removePresence(String groupId, String userId) {
        }

        @Override
        public List<OnlineMember> members(String groupId) {
            return List.of();
        }

        @Override
        public List<OnlineMember> onlineMembers() {
            return List.of();
        }

        @Override
        public void publish(ChatEvent event) {
            events.add(event);
        }
    }
}
