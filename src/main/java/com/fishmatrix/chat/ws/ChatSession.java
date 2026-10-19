package com.fishmatrix.chat.ws;

import org.springframework.web.socket.WebSocketSession;

public class ChatSession {

    private final WebSocketSession webSocketSession;
    private final String userId;
    private final String nickname;
    private final String node;
    private volatile String groupId;

    public ChatSession(
            WebSocketSession webSocketSession,
            String userId,
            String nickname,
            String node) {
        this.webSocketSession = webSocketSession;
        this.userId = userId;
        this.nickname = nickname;
        this.node = node;
    }

    public WebSocketSession getWebSocketSession() {
        return webSocketSession;
    }

    public String getSessionId() {
        return webSocketSession.getId();
    }

    public String getUserId() {
        return userId;
    }

    public String getNickname() {
        return nickname;
    }

    public String getNode() {
        return node;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }
}
