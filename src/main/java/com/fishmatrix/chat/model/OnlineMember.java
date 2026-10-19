package com.fishmatrix.chat.model;

public record OnlineMember(
        String groupId,
        String userId,
        String nickname,
        String node,
        long joinedAt) {
}
