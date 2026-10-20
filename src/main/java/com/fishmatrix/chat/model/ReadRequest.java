package com.fishmatrix.chat.model;

public record ReadRequest(String groupId, String peerUserId, Long seq) {
}
