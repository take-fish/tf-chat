package com.fishmatrix.chat.agent;

import com.fishmatrix.chat.model.ChatIdentity;
import java.util.List;

/**
 * 代表「@ 智能助手」的那个人，用于给 tf-agent 签发身份头。
 *
 * <p>tf-agent 的权限、审计都按真实用户算：谁 @ 的，用谁的 identity 和权限，
 * 聊天服务只是转发，不自己造一个高权限服务账号。</p>
 */
public record AgentCaller(
        long userId,
        String username,
        String nickname,
        String role,
        List<String> roles,
        List<String> permissions) {

    public AgentCaller {
        username = username == null ? "" : username;
        nickname = nickname == null ? "" : nickname;
        role = role == null ? "" : role;
        roles = roles == null ? List.of() : List.copyOf(roles);
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
    }

    public static AgentCaller of(ChatIdentity identity) {
        return new AgentCaller(
                identity.accountId(),
                identity.username(),
                identity.nickname(),
                identity.role(),
                identity.roles(),
                identity.permissions());
    }

    /** agent 侧展示名兜底，和 CurrentUser.display_name 的规则保持一致。 */
    public String displayName() {
        if (!nickname.isBlank()) {
            return nickname;
        }
        return username.isBlank() ? "user-" + userId : username;
    }
}
