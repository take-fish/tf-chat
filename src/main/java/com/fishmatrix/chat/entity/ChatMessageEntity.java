package com.fishmatrix.chat.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fishmatrix.chat.domain.MessageScope;
import java.time.Instant;
import lombok.Data;

/**
 * 私聊消息。主键 seq 是全局自增位点，前端用它做游标分页；message_id 是业务唯一键，
 * (from_user_id, client_msg_id) 唯一索引保证客户端重发幂等。
 */
@Data
@TableName("chat_message")
public class ChatMessageEntity {

    @TableId(type = IdType.AUTO)
    private Long seq;

    private String messageId;

    private String conversationId;

    private MessageScope scope;

    private String groupId;

    private String fromUserId;

    private String fromNickname;

    private String fromNode;

    private String toUserId;

    private String content;

    private String kind;

    private String clientMsgId;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;

    private Instant recalledAt;

    private String recalledBy;
}
