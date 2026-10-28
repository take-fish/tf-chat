package com.fishmatrix.chat.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Data;

/** 聊天群组，data seed 由 Flyway / schema-h2.sql 写入。 */
@Data
@TableName("chat_group")
public class ChatGroupEntity {

    @TableId(type = IdType.INPUT)
    private String id;

    private String name;

    private String description;

    private Integer sortOrder;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;
}
