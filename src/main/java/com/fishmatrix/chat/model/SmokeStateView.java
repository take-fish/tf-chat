package com.fishmatrix.chat.model;

import java.util.List;

/**
 * 推送给某一个登录用户的冒烟测试快照。
 * pendingInvites 只代表当前用户的待处理邀请，不会把其他人的邀请广播出去。
 */
public record SmokeStateView(
        int count,
        List<SmokingMember> smoking,
        List<OnlineMember> online,
        List<Invite> invites,
        Session mySession) {

    public record SmokingMember(
            String sessionId,
            String userId,
            String nickname,
            boolean initiator) {
    }

    public record Invite(
            String sessionId,
            String fromUserId,
            String fromNickname,
            long invitedAt) {
    }

    public record Session(
            String id,
            String status,
            String initiatorUserId,
            String initiatorNickname,
            List<Participant> participants,
            List<PendingInvite> pendingInvites) {
    }

    public record Participant(String userId, String nickname, long joinedAt) {
    }

    public record PendingInvite(String userId, String nickname, long invitedAt) {
    }
}
