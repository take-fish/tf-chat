package com.fishmatrix.chat.agent;

import com.fishmatrix.chat.config.ChatAgentProperties;
import com.fishmatrix.chat.config.ChatProperties;
import com.fishmatrix.chat.converter.ChatConverters;
import com.fishmatrix.chat.entity.ChatMessageEntity;
import com.fishmatrix.chat.mapper.ChatMessageMapper;
import com.fishmatrix.chat.model.ChatEvent;
import com.fishmatrix.chat.model.ChatIdentity;
import com.fishmatrix.chat.model.ChatMessage;
import com.fishmatrix.chat.service.ChatImageService;
import com.fishmatrix.chat.store.ChatStore;
import com.fishmatrix.chat.util.ConversationIds;
import com.fishmatrix.common.trace.TraceIds;
import jakarta.annotation.PreDestroy;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 「@展示名」触发智能助手，并把回答按片段播给当前会话。
 *
 * <p>几个刻意的约束：
 * <ul>
 *   <li>发送线程只做「识别 + 提交」，绝不等模型；回答在独立线程池里跑。</li>
 *   <li>同一会话同时只有一轮在跑（单飞），避免群里连发几条 @ 把 GPU 打满。</li>
 *   <li>片段是增量文本，带上同一个 {@code streamId}，前端按 streamId 合并成一个块。</li>
 *   <li>私聊只在结束时落一条完整消息（历史干净），实时过程不写库。</li>
 * </ul>
 */
@Service
@Slf4j
public class AgentReplyService {

    static final String BOT_USER_ID = "agent";
    static final String BOT_NODE = "fm-agent";

    private static final Pattern UNSAFE_CONTROL = Pattern.compile(
            "[\\u0000-\\u0008\\u000B-\\u001F\\u007F-\\u009F\\u202A-\\u202E\\u2066-\\u2069]");
    private static final String TRUNCATED_SUFFIX = "\n…（回答过长，已截断）";

    private final ChatAgentProperties properties;
    private final ChatProperties chatProperties;
    private final AgentProfileCache profile;
    private final AgentApiClient client;
    private final ChatStore store;
    private final ChatMessageMapper messageMapper;
    private final AgentImageCache imageCache;
    private final ChatImageService images;
    private final ThreadPoolExecutor executor;
    private final ConcurrentHashMap<String, Boolean> inFlight = new ConcurrentHashMap<>();

    public AgentReplyService(
            ChatAgentProperties properties,
            ChatProperties chatProperties,
            AgentProfileCache profile,
            AgentApiClient client,
            ChatStore store,
            ChatMessageMapper messageMapper,
            AgentImageCache imageCache,
            ChatImageService images) {
        this.properties = properties;
        this.chatProperties = chatProperties;
        this.profile = profile;
        this.client = client;
        this.store = store;
        this.messageMapper = messageMapper;
        this.imageCache = imageCache;
        this.images = images;
        this.executor = new ThreadPoolExecutor(
                properties.maxConcurrentTurns(),
                properties.maxConcurrentTurns(),
                30L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(properties.queueCapacity()),
                runnable -> {
                    Thread thread = new Thread(runnable, "chat-agent-turn");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
        this.executor.allowCoreThreadTimeOut(true);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    /** 群消息发出去之后的钩子；没有 @ 就静默返回。 */
    public void afterGroupMessage(ChatIdentity sender, String groupId, String content) {
        trigger(ConversationIds.group(groupId), sender, content, new GroupSink(groupId));
    }

    /** 私聊消息发出去之后的钩子；回答进同一个私聊会话，双方都能看到。 */
    public void afterDirectMessage(ChatIdentity sender, String peerUserId, String content) {
        trigger(
                ConversationIds.direct(sender.userId(), peerUserId),
                sender,
                content,
                new DirectSink(sender.userId(), peerUserId));
    }

    private void trigger(String conversationId, ChatIdentity sender, String content, ReplySink sink) {
        if (!properties.enabled()) {
            return;
        }
        String name = profile.mentionName();
        if (!properties.usable(name)) {
            return;
        }
        var mention = AgentMention.parse(content, name);
        if (mention.isEmpty()) {
            return;
        }
        AgentImageCache.ImageRef imageRef =
                imageCache.get(conversationId, sender.userId()).orElse(null);
        if (inFlight.putIfAbsent(conversationId, Boolean.TRUE) != null) {
            sink.text("我正在回答上一条，等这条说完再 @ 我一次");
            return;
        }
        try {
            executor.execute(() -> runTurn(conversationId, sender, mention.get(), imageRef, sink));
        } catch (RejectedExecutionException exception) {
            inFlight.remove(conversationId);
            sink.text("智能助手正忙，稍后再 @ 我");
        }
    }

    private void runTurn(
            String conversationId,
            ChatIdentity sender,
            AgentMention mention,
            AgentImageCache.ImageRef imageRef,
            ReplySink sink) {
        String streamId = UUID.randomUUID().toString();
        StreamBuffer buffer = new StreamBuffer(sink, streamId);
        AtomicBoolean finished = new AtomicBoolean();
        try {
            // 文件/数据库读取放在工作线程，不在 WebSocket 发送线程里做。
            AgentImage image = resolveImage(imageRef, sender);
            client.stream(
                    AgentCaller.of(sender),
                    sentence(mention.prompt()),
                    "chat:" + conversationId,
                    image,
                    new AgentStreamListener() {
                        @Override
                        public void onDelta(String text) {
                            buffer.append(text);
                        }

                        @Override
                        public void onComplete(String fullText) {
                            finished.set(true);
                            buffer.finish(fullText);
                        }

                        @Override
                        public void onError(String message) {
                            finished.set(true);
                            buffer.fail(message);
                        }
                    });
            if (!finished.get()) {
                buffer.finish("");
            }
        } catch (AgentUnavailableException exception) {
            buffer.fail(exception.getMessage());
        } catch (RuntimeException exception) {
            log.warn("智能助手回合异常: {}", exception.getMessage(), exception);
            buffer.fail("智能助手处理失败，请稍后再试");
        } finally {
            inFlight.remove(conversationId);
        }
    }

    /**
     * 把缓存里的图片引用解析成请求体。过期/无权/文件缺失都按纯文本继续，不阻断回答。
     */
    private AgentImage resolveImage(AgentImageCache.ImageRef ref, ChatIdentity sender) {
        if (ref == null) {
            return null;
        }
        try {
            ChatImageService.StoredImage stored = switch (ref.kind()) {
                case GROUP_DATA_URL -> images.readGroupDataUrl(ref.value());
                case DIRECT_IMAGE_ID -> images.load(sender, ref.value());
            };
            if (stored == null || stored.bytes() == null || stored.bytes().length == 0
                    || stored.contentType() == null || stored.contentType().isBlank()) {
                return null;
            }
            return new AgentImage(
                    stored.contentType(),
                    Base64.getEncoder().encodeToString(stored.bytes()),
                    stored.bytes().length);
        } catch (RuntimeException exception) {
            // 只记原因，不打印图片内容 / Base64。
            log.warn("读取 @ 提问的图片失败: {}", exception.getMessage());
            return null;
        }
    }

    /** 片段缓冲：攒到一句/一段再发，既不刷屏也不长时间没动静。 */
    private final class StreamBuffer {

        private final ReplySink sink;
        private final String streamId;
        private final StringBuilder text = new StringBuilder();
        private final StringBuilder pending = new StringBuilder();
        private long lastFlushAt = System.currentTimeMillis();
        private boolean truncated;

        StreamBuffer(ReplySink sink, String streamId) {
            this.sink = sink;
            this.streamId = streamId;
        }

        void append(String delta) {
            if (delta == null || delta.isEmpty() || truncated) {
                return;
            }
            int room = properties.maxReplyChars() - text.length();
            if (room <= 0) {
                truncated = true;
                flush();
                sink.text(TRUNCATED_SUFFIX.strip());
                return;
            }
            String piece = delta.length() > room ? delta.substring(0, room) : delta;
            if (piece.length() < delta.length()) {
                truncated = true;
            }
            text.append(piece);
            pending.append(piece);
            if (due()) {
                flush();
            }
        }

        void finish(String fullText) {
            if (text.length() == 0 && fullText != null && !fullText.isBlank()) {
                // 对端只在 done 里给完整答案（没有 delta）时兜底，保证不会空回复。
                pending.append(fullText);
                text.append(fullText);
            }
            flush();
            if (text.length() == 0) {
                sink.text("这次没查到结果，换个问法或缩小范围再试试");
                return;
            }
            // 私聊历史只有这一条，截断提示也要跟着落库，否则回看时像是被吞了半句
            sink.persist(truncated ? text + TRUNCATED_SUFFIX : text.toString());
        }

        void fail(String message) {
            flush();
            sink.text("智能助手暂时不可用：" + (message == null || message.isBlank() ? "请稍后再试" : message));
        }

        private boolean due() {
            if (pending.length() >= properties.chunkMaxChars()) {
                return true;
            }
            if (pending.length() >= properties.chunkMinChars() && endsSentence(pending)) {
                return true;
            }
            return pending.length() > 0
                    && System.currentTimeMillis() - lastFlushAt
                            >= properties.chunkIntervalMs();
        }

        private void flush() {
            if (pending.length() == 0) {
                return;
            }
            String chunk = sanitize(pending.toString());
            pending.setLength(0);
            lastFlushAt = System.currentTimeMillis();
            if (!chunk.isEmpty()) {
                sink.chunk(streamId, chunk);
            }
        }

        private boolean endsSentence(CharSequence value) {
            char last = value.charAt(value.length() - 1);
            return "\n。！？!?.;；：:".indexOf(last) >= 0;
        }
    }

    /** 一条消息往哪里去：群广播 / 私聊双方。 */
    private interface ReplySink {

        void chunk(String streamId, String text);

        /** 非流式的整条提示（权限、忙、失败兜底）。 */
        void text(String text);

        /** 会话结束时落库（群聊不需要）。 */
        void persist(String fullText);
    }

    private final class GroupSink implements ReplySink {

        private final String groupId;

        GroupSink(String groupId) {
            this.groupId = groupId;
        }

        @Override
        public void chunk(String streamId, String text) {
            store.publish(ChatEvent.groupMessage(
                    message(groupId, null, text, streamId)));
        }

        @Override
        public void text(String text) {
            store.publish(ChatEvent.groupMessage(message(groupId, null, text, null)));
        }

        @Override
        public void persist(String fullText) {
            // 群聊是临时会话，不落库
        }
    }

    private final class DirectSink implements ReplySink {

        private final String requesterId;
        private final String peerId;

        DirectSink(String requesterId, String peerId) {
            this.requesterId = requesterId;
            this.peerId = peerId;
        }

        @Override
        public void chunk(String streamId, String text) {
            ChatMessage message = message(null, peerId, text, streamId);
            publishToParticipants(message);
        }

        @Override
        public void text(String text) {
            publishToParticipants(message(null, peerId, text, null));
        }

        @Override
        public void persist(String fullText) {
            try {
                ChatMessage message = message(null, peerId, fullText, null);
                ChatMessageEntity entity = ChatConverters.toEntity(message);
                // 助手是会话里的第三方：这条消息必须落在双方这条私聊里，
                // 不能按 from/to 推成 dm:agent:<peer>，否则双方都看不到历史。
                entity.setConversationId(ConversationIds.direct(requesterId, peerId));
                messageMapper.insert(entity);
            } catch (RuntimeException exception) {
                log.warn("保存智能助手私聊回答失败: {}", exception.getMessage());
            }
        }

        private void publishToParticipants(ChatMessage message) {
            // 助手是会话里的第三方：from 是 agent，收件人要从 toUserId 认出「对面那个真人」。
            // 所以给双方各发一份，toUserId 指向各自的会话对端，前端据此判断是不是当前私聊。
            String traceId = TraceIds.currentTraceId();
            store.publish(new ChatEvent(
                    "dm",
                    requesterId,
                    message.withTo(peerId),
                    null,
                    traceId));
            if (!requesterId.equals(peerId)) {
                store.publish(new ChatEvent(
                        "dm",
                        peerId,
                        message.withTo(requesterId),
                        null,
                        traceId));
            }
        }
    }

    private ChatMessage message(String groupId, String toUserId, String content, String streamId) {
        return new ChatMessage(
                UUID.randomUUID().toString(),
                0L,
                groupId,
                BOT_USER_ID,
                botNickname(),
                BOT_NODE,
                toUserId,
                content,
                ChatMessage.KIND_TEXT,
                System.currentTimeMillis(),
                null,
                false,
                streamId);
    }

    private String botNickname() {
        String name = profile.mentionName();
        if (name == null || name.isBlank()) {
            name = "智能助手";
        }
        int max = chatProperties.maxNicknameLength();
        return name.codePointCount(0, name.length()) > max
                ? name.substring(0, name.offsetByCodePoints(0, max))
                : name;
    }

    private String sentence(String prompt) {
        String cleaned = prompt == null ? "" : prompt.strip();
        int max = properties.maxPromptChars();
        if (cleaned.codePointCount(0, cleaned.length()) > max) {
            cleaned = cleaned.substring(0, cleaned.offsetByCodePoints(0, max));
        }
        return cleaned;
    }

    private static String sanitize(String value) {
        return UNSAFE_CONTROL.matcher(value.replace("\r\n", "\n")).replaceAll("").strip();
    }

    /** 测试用：当前是否还有回合在跑。 */
    List<String> activeConversations() {
        return List.copyOf(inFlight.keySet());
    }
}
