package com.fishmatrix.chat.model;

import java.util.List;

/**
 * 聊天侧使用的登录身份。userId 统一映射为 u{accountId}，与前端 identity store 保持一致。
 *
 * <p>username / roles / permissions 只在服务端之间流转（例如 @智能助手 时代替用户
 * 向 tf-agent 发起调用），不进任何客户端协议。旧 ticket 里没有这些字段时归一化为空，
 * 行为退化为「无权使用智能助手」，不会误放行。</p>
 */
public record ChatIdentity(
        long accountId,
        String userId,
        String nickname,
        String role,
        String username,
        List<String> roles,
        List<String> permissions) {

    public ChatIdentity {
        username = username == null ? "" : username;
        role = role == null ? "" : role;
        roles = roles == null ? List.of() : List.copyOf(roles);
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
    }

    public static ChatIdentity of(long accountId, String nickname, String role) {
        return new ChatIdentity(accountId, "u" + accountId, nickname, role, "", List.of(), List.of());
    }

    public static ChatIdentity of(
            long accountId,
            String nickname,
            String role,
            String username,
            List<String> roles,
            List<String> permissions) {
        return new ChatIdentity(
                accountId, "u" + accountId, nickname, role, username, roles, permissions);
    }
}
