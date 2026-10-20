package com.fishmatrix.chat.service;

import com.fishmatrix.chat.model.ChatEvent;
import com.fishmatrix.chat.model.ChatMessage;
import com.fishmatrix.chat.model.OnlineMember;
import com.fishmatrix.chat.store.ChatStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class FakeChatStore implements ChatStore {

    final Map<String, List<ChatMessage>> offline = new HashMap<>();
    final Map<String, List<OnlineMember>> presence = new HashMap<>();
    final List<OnlineMember> online = new ArrayList<>();
    final List<ChatEvent> events = new ArrayList<>();

    @Override
    public void addOfflineMessage(String userId, ChatMessage message) {
        offline.computeIfAbsent(userId, key -> new ArrayList<>()).add(message);
    }

    @Override
    public List<ChatMessage> drainOfflineMessages(String userId) {
        List<ChatMessage> messages = offline.remove(userId);
        return messages == null ? List.of() : messages;
    }

    @Override
    public void markOnline(OnlineMember member, long ttlMs) {
        online.removeIf(existing -> existing.userId().equals(member.userId()));
        online.add(member);
    }

    @Override
    public boolean isOnline(String userId) {
        return online.stream().anyMatch(member -> member.userId().equals(userId));
    }

    @Override
    public void markOffline(String userId) {
        online.removeIf(member -> member.userId().equals(userId));
    }

    @Override
    public void upsertPresence(OnlineMember member, long ttlMs) {
        List<OnlineMember> members = presence.computeIfAbsent(
                member.groupId(), key -> new ArrayList<>());
        members.removeIf(existing -> existing.userId().equals(member.userId()));
        members.add(member);
    }

    @Override
    public void removePresence(String groupId, String userId) {
        List<OnlineMember> members = presence.get(groupId);
        if (members != null) {
            members.removeIf(member -> member.userId().equals(userId));
        }
    }

    @Override
    public List<OnlineMember> members(String groupId) {
        return List.copyOf(presence.getOrDefault(groupId, List.of()));
    }

    @Override
    public List<OnlineMember> onlineMembers() {
        return List.copyOf(online);
    }

    @Override
    public void publish(ChatEvent event) {
        events.add(event);
    }
}
