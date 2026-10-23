package com.fishmatrix.chat.store;

import java.util.List;

/**
 * 冒烟测试会话（Redis 存储结构）。
 * 发起人先处于 WAITING，至少一人接受后变为 ACTIVE；手动结束后整条记录删除。
 */
public record SmokeSession(
        String id,
        String initiatorUserId,
        String initiatorNickname,
        String status,
        List<Participant> participants,
        List<Invite> pendingInvites,
        long createdAt,
        long updatedAt) {

    public static final String STATUS_WAITING = "WAITING";
    public static final String STATUS_ACTIVE = "ACTIVE";

    public record Participant(String userId, String nickname, long joinedAt) {
    }

    public record Invite(
            String userId,
            String nickname,
            String invitedByUserId,
            String invitedByNickname,
            long invitedAt) {
    }
}
