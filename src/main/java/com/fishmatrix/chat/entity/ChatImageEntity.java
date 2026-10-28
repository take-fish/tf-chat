package com.fishmatrix.chat.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Data;

/** 私聊图片元数据；文件本体落在 CHAT_IMAGE_DIR，库里只存索引。 */
@Data
@TableName("chat_image")
public class ChatImageEntity {

    @TableId(type = IdType.INPUT)
    private String id;

    private String ownerUserId;

    private String contentType;

    private Long size;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;
}
