package com.fishmatrix.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fishmatrix.chat.entity.ChatMessageEntity;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessageEntity> {

    ChatMessageEntity selectByMessageId(@Param("messageId") String messageId);

    /** 幂等写入的判重口径：同一发送者 + 同一 clientMsgId 视为同一条消息。 */
    ChatMessageEntity selectByClient(@Param("fromUserId") String fromUserId,
            @Param("clientMsgId") String clientMsgId);

    /** 拉取 afterSeq 之后的新消息，按 seq 升序。 */
    List<ChatMessageEntity> selectAfter(@Param("conversationId") String conversationId,
            @Param("afterSeq") long afterSeq, @Param("fetch") int fetch);

    /** 拉取 beforeSeq 之前的旧消息，按 seq 降序（调用方负责反转）。 */
    List<ChatMessageEntity> selectBefore(@Param("conversationId") String conversationId,
            @Param("beforeSeq") Long beforeSeq, @Param("fetch") int fetch);

    List<ChatMessageEntity> selectSearch(@Param("conversationId") String conversationId,
            @Param("pattern") String pattern, @Param("limit") int limit);

    /** 条件撤回：只有未撤回的消息会被更新，返回影响行数。 */
    int markRecalled(@Param("messageId") String messageId,
            @Param("userId") String userId, @Param("now") Instant now);

    Long selectLatestSeq(@Param("conversationId") String conversationId);

    List<String> selectDirectConversations(@Param("userId") String userId);

    int countUnread(@Param("conversationId") String conversationId,
            @Param("seq") long seq, @Param("userId") String userId);

    /** 图片是否已被某条私聊消息引用，且该会话参与方包含 userId。 */
    int countImageRefs(@Param("imageId") String imageId, @Param("userId") String userId);
}
