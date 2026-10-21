package com.fishmatrix.chat.model;

/**
 * 私聊图片元数据。文件本身存放在 CHAT_IMAGE_DIR，数据库只保存索引。
 */
public record ChatImage(
        String id,
        String ownerUserId,
        String contentType,
        long size,
        long createdAt) {
}
