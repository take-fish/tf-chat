package com.fishmatrix.chat.service;

import com.fishmatrix.chat.model.ChatEvent;
import com.fishmatrix.chat.model.ChatGroup;
import com.fishmatrix.chat.model.ChatMessage;
import com.fishmatrix.chat.model.OnlineMember;
import com.fishmatrix.chat.store.ChatStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

class FakeChatStore implements ChatStore {

    final Map<String, ChatGroup> groups = new LinkedHashMap<>();
    final Map<String, List<ChatMessage>> groupHistories = new HashMap<>();
    final Map<String, List<ChatMessage>> dmHistories = new HashMap<>();
    final Map<String, List<ChatMessage>> offline = new HashMap<>();
    final Map<String, List<OnlineMember>> presence = new HashMap<>();
    final Set<String> online = new HashSet<>();
    final List<ChatEvent> events = new ArrayList<>();

    @Override
    public void ensureGroups() {
        if (groups.isEmpty()) {
            groups.put("dev-group", new ChatGroup("dev-group", "研发协作", "日常研发"));
            groups.put("ops-oncall", new ChatGroup("ops-oncall", "运维值班", "告警协同"));
            groups.put("tech-share", new ChatGroup("tech-share", "技术分享", "方案交流"));
        }
    }

    @Override
    public List<ChatGroup> groups() {
        return new ArrayList<>(groups.values());
    }

    @Override
    public boolean groupExists(String groupId) {
        return groups.containsKey(groupId);
    }

    @Override
    public ChatMessage appendGroupMessage(String groupId, ChatMessage message) {
        groupHistories.computeIfAbsent(groupId, key -> new ArrayList<>()).add(message);
        return message;
    }

    @Override
    public List<ChatMessage> groupHistory(String groupId, int limit) {
        List<ChatMessage> messages = groupHistories.getOrDefault(groupId, List.of());
        return messages.subList(Math.max(0, messages.size() - limit), messages.size());
    }

    @Override
    public ChatMessage appendDirectMessage(ChatMessage message) {
        dmHistories
                .computeIfAbsent(message.fromUserId() + ":" + message.toUserId(), key -> new ArrayList<>())
                .add(message);
        return message;
    }

    @Override
    public List<ChatMessage> directHistory(String userA, String userB, int limit) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.addAll(dmHistories.getOrDefault(userA + ":" + userB, List.of()));
        messages.addAll(dmHistories.getOrDefault(userB + ":" + userA, List.of()));
        return messages;
    }

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
        online.add(member.userId());
    }

    @Override
    public boolean isOnline(String userId) {
        return online.contains(userId);
    }

    @Override
    public void markOffline(String userId) {
        online.remove(userId);
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
    public void publish(ChatEvent event) {
        events.add(event);
    }
}
