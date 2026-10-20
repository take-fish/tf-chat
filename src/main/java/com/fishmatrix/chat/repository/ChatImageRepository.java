package com.fishmatrix.chat.repository;

import com.fishmatrix.chat.model.ChatImage;

public interface ChatImageRepository {

    ChatImage insert(ChatImage image);

    ChatImage find(String id);

    /**
     * 图片是否已被一条私聊消息引用，且该私聊参与者包含 userId。
     */
    boolean isDirectParticipant(String imageId, String userId);
}
