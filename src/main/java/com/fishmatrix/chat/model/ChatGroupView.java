package com.fishmatrix.chat.model;

public record ChatGroupView(
        String id,
        String name,
        String description,
        int onlineCount,
        int unread) {
}
