package com.fishmatrix.chat.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fishmatrix.chat.domain.MessageScope;
import com.fishmatrix.chat.entity.ChatMessageEntity;
import com.fishmatrix.chat.util.ConversationIds;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

/**
 * SQL 层测试：游标分页、模糊搜索转义、条件撤回、未读统计都依赖手写 XML，
 * 这里直接在 H2（MODE=MySQL）上验证语义，避免只靠 service 测试间接覆盖。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Transactional
class ChatMessageMapperTest {

    @Autowired
    private ChatMessageMapper messageMapper;

    @Autowired
    private ChatGroupMapper groupMapper;

    @Autowired
    private ChatReadStateMapper readStateMapper;

    @Test
    void readsDefaultGroupsSeededBySchema() {
        assertThat(groupMapper.selectAllOrdered()).extracting("id")
                .containsExactly("dev-group", "ops-oncall", "tech-share");
        assertThat(groupMapper.selectCount(null)).isEqualTo(3L);
        assertThat(groupMapper.selectById("dev-group")).isNotNull();
        assertThat(groupMapper.selectById("nope")).isNull();
    }

    @Test
    void insertFillsGeneratedSeqAndFiltersByConversation() {
        ChatMessageEntity first = insert(group("m-1", "一"));
        ChatMessageEntity second = insert(group("m-2", "二"));
        insert(direct("d-1", "u1", "u2", "hi"));

        assertThat(first.getSeq()).isPositive();
        assertThat(second.getSeq()).isGreaterThan(first.getSeq());
        assertThat(messageMapper.selectByMessageId("m-1").getContent()).isEqualTo("一");
        assertThat(messageMapper.selectByMessageId("missing")).isNull();
        assertThat(messageMapper.selectByClient("u1", "client-1")).isNull();
    }

    @Test
    void paginatesHistoryWithCursors() {
        ChatMessageEntity first = insert(group("m-1", "一"));
        ChatMessageEntity second = insert(group("m-2", "二"));
        ChatMessageEntity third = insert(group("m-3", "三"));

        List<ChatMessageEntity> latest = messageMapper.selectBefore(
                ConversationIds.group("dev-group"), null, 3);
        assertThat(latest).extracting(ChatMessageEntity::getMessageId)
                .containsExactly("m-3", "m-2", "m-1");

        List<ChatMessageEntity> older = messageMapper.selectBefore(
                ConversationIds.group("dev-group"), second.getSeq(), 3);
        assertThat(older).extracting(ChatMessageEntity::getMessageId)
                .containsExactly("m-1");

        List<ChatMessageEntity> missed = messageMapper.selectAfter(
                ConversationIds.group("dev-group"), first.getSeq(), 10);
        assertThat(missed).extracting(ChatMessageEntity::getMessageId)
                .containsExactly("m-2", "m-3");
        assertThat(messageMapper.selectLatestSeq(ConversationIds.group("dev-group")))
                .isEqualTo(third.getSeq());
    }

    @Test
    void searchEscapesLikeWildcardsAndSkipsRecalled() {
        insert(group("m-1", "进度 100% 完成"));
        insert(group("m-2", "进度 1000 完成"));
        insert(group("m-3", "a_b 约定"));
        insert(group("m-4", "axb 约定"));
        ChatMessageEntity recalled = insert(group("m-5", "100% 但要撤回"));
        messageMapper.markRecalled(recalled.getMessageId(), "u1", Instant.now());

        String conversationId = ConversationIds.group("dev-group");
        assertThat(messageMapper.selectSearch(conversationId, "%100!%%", 10))
                .extracting(ChatMessageEntity::getMessageId)
                .containsExactly("m-1");
        assertThat(messageMapper.selectSearch(conversationId, "%a!_b%", 10))
                .extracting(ChatMessageEntity::getMessageId)
                .containsExactly("m-3");
    }

    @Test
    void markRecalledOnlyAppliesOnce() {
        ChatMessageEntity own = insert(direct("m-1", "u1", "u2", "要撤回"));
        Instant now = Instant.now();

        assertThat(messageMapper.markRecalled(own.getMessageId(), "u1", now)).isEqualTo(1);
        ChatMessageEntity recalled = messageMapper.selectByMessageId(own.getMessageId());
        assertThat(recalled.getContent()).isEmpty();
        assertThat(recalled.getRecalledAt()).isNotNull();
        assertThat(recalled.getRecalledBy()).isEqualTo("u1");
        assertThat(messageMapper.selectSearch(
                ConversationIds.direct("u1", "u2"), "%撤回%", 10)).isEmpty();

        assertThat(messageMapper.markRecalled(own.getMessageId(), "u1", now)).isZero();
    }

    @Test
    void duplicateClientMessageIdIsRejectedByUniqueIndex() {
        ChatMessageEntity first = insert(withClient(group("m-1", "hello"), "client-1"));

        assertThatThrownBy(() -> insert(withClient(group("m-2", "hello"), "client-1")))
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(messageMapper.selectByClient("u1", "client-1").getMessageId())
                .isEqualTo(first.getMessageId());
        // 换一个发送者则允许同样的 clientMsgId
        ChatMessageEntity other = insert(withClient(groupFrom("m-3", "u2", "hello"), "client-1"));
        assertThat(other.getSeq()).isPositive();
    }

    @Test
    void unreadCountsOnlyIncomingUnrecalledDirectMessages() {
        insert(direct("d-1", "u1", "u2", "self"));
        ChatMessageEntity incoming = insert(direct("d-2", "u2", "u1", "from peer"));
        insert(direct("d-3", "u2", "u1", "recalled one"));
        ChatMessageEntity recalled = messageMapper.selectByMessageId("d-3");
        messageMapper.markRecalled(recalled.getMessageId(), "u2", Instant.now());

        String conversationId = ConversationIds.direct("u1", "u2");
        assertThat(messageMapper.selectDirectConversations("u1")).containsExactly(conversationId);
        assertThat(messageMapper.countUnread(conversationId, 0L, "u1")).isEqualTo(1);

        readStateMapper.insertReadState("u1", conversationId, incoming.getSeq(), Instant.now());
        assertThat(readStateMapper.selectLastReadSeq("u1", conversationId))
                .isEqualTo(incoming.getSeq());
        assertThat(messageMapper.countUnread(conversationId, incoming.getSeq(), "u1")).isZero();
    }

    @Test
    void readStateNeverMovesBackwards() {
        String conversationId = ConversationIds.direct("u1", "u2");
        Instant now = Instant.now();

        assertThat(readStateMapper.updateLastReadSeq("u1", conversationId, 5, now)).isZero();
        assertThat(readStateMapper.insertReadState("u1", conversationId, 5, now)).isEqualTo(1);
        assertThat(readStateMapper.updateLastReadSeq("u1", conversationId, 3, now)).isEqualTo(1);
        assertThat(readStateMapper.selectLastReadSeq("u1", conversationId)).isEqualTo(5L);
        assertThat(readStateMapper.updateLastReadSeq("u1", conversationId, 9, now)).isEqualTo(1);
        assertThat(readStateMapper.selectLastReadSeq("u1", conversationId)).isEqualTo(9L);
    }

    @Test
    void countsImageReferencesPerParticipant() {
        ChatMessageEntity image = insert(direct("d-1", "u1", "u2", "image-1"));
        image.setKind("image");
        messageMapper.updateById(image);

        assertThat(messageMapper.countImageRefs("image-1", "u1")).isEqualTo(1);
        assertThat(messageMapper.countImageRefs("image-1", "u2")).isEqualTo(1);
        assertThat(messageMapper.countImageRefs("image-1", "u3")).isZero();
        assertThat(messageMapper.countImageRefs("image-2", "u1")).isZero();
    }

    private ChatMessageEntity insert(ChatMessageEntity entity) {
        messageMapper.insert(entity);
        return entity;
    }

    private static ChatMessageEntity group(String id, String content) {
        return groupFrom(id, "u1", content);
    }

    private static ChatMessageEntity groupFrom(String id, String from, String content) {
        ChatMessageEntity entity = base(id, from, content);
        entity.setConversationId(ConversationIds.group("dev-group"));
        entity.setScope(MessageScope.GROUP);
        entity.setGroupId("dev-group");
        return entity;
    }

    private static ChatMessageEntity direct(String id, String from, String to, String content) {
        ChatMessageEntity entity = base(id, from, content);
        entity.setConversationId(ConversationIds.direct(from, to));
        entity.setScope(MessageScope.DM);
        entity.setToUserId(to);
        return entity;
    }

    private static ChatMessageEntity base(String id, String from, String content) {
        ChatMessageEntity entity = new ChatMessageEntity();
        entity.setMessageId(id);
        entity.setFromUserId(from);
        entity.setFromNickname(from.equals("u1") ? "甲" : "乙");
        entity.setFromNode("dev-node-01");
        entity.setContent(content);
        entity.setKind("text");
        entity.setCreatedAt(Instant.now());
        return entity;
    }

    private static ChatMessageEntity withClient(ChatMessageEntity entity, String clientMsgId) {
        entity.setClientMsgId(clientMsgId);
        return entity;
    }
}
