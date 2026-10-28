package com.fishmatrix.chat.domain;

import com.baomidou.mybatisplus.annotation.IEnum;

/** 消息存储范围：群聊只在内存/广播中流转，只有 DM 落库。 */
public enum MessageScope implements IEnum<String> {
    GROUP,
    DM;

    @Override
    public String getValue() {
        return name();
    }
}
