package com.fishmatrix.chat.model;

/**
 * 聊天侧使用的登录身份。userId 统一映射为 u{accountId}，与前端 identity store 保持一致。
 */
public record ChatIdentity(
        long accountId,
        String userId,
        String nickname,
        String role) {

    public static ChatIdentity of(long accountId, String nickname, String role) {
        return new ChatIdentity(accountId, "u" + accountId, nickname, role);
    }
}
