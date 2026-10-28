package com.fishmatrix.chat.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Data;

/** 私聊已读位点，复合主键 (user_id, conversation_id)，因此没有 @TableId。 */
@Data
@TableName("chat_read_state")
public class ChatReadStateEntity {

    private String userId;

    private String conversationId;

    private Long lastReadSeq;

    private Instant updatedAt;
}
