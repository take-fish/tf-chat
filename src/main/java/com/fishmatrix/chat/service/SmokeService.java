package com.fishmatrix.chat.service;

import com.fishmatrix.chat.model.ChatEvent;
import com.fishmatrix.chat.error.ChatException;
import com.fishmatrix.chat.model.ChatIdentity;
import com.fishmatrix.chat.model.OnlineMember;
import com.fishmatrix.chat.model.SmokeStateView;
import com.fishmatrix.chat.store.ChatStore;
import com.fishmatrix.chat.store.SmokeSession;
import com.fishmatrix.chat.store.SmokeStore;
import com.fishmatrix.chat.ws.ChatSession;
import com.fishmatrix.chat.ws.ChatSessionRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 冒烟测试：轻量的多人状态协作。
 *
 * <p>发起人邀请在线用户，对方接受后该会话进入 ACTIVE，会话内所有人计入“正在冒烟测试”；
 * 可继续邀请更多人，结束由用户手动触发。状态放在 Redis，WebSocket 通过 Redis 广播刷新。</p>
 */
@Service
@RequiredArgsConstructor
public class SmokeService {

    /** 兜底清理超过 12 小时未变化的会话，避免异常退出后永久占位。 */
    private static final long MAX_SESSION_AGE_MS = Duration.ofHours(12).toMillis();

    private final SmokeStore smokeStore;
    private final ChatStore chatStore;
    private final ChatSessionRegistry registry;
    private final Object lock = new Object();

    public SmokeStateView stateFor(String userId) {
        List<SmokeSession> sessions;
        synchronized (lock) {
            sessions = loadSessions();
        }
        return buildState(sessions, chatStore.onlineMembers(), userId);
    }

    public void invite(ChatIdentity actor, String targetUserId) {
        ChatService.requireUserId(targetUserId);
        if (actor.userId().equals(targetUserId)) {
            throw ChatException.badRequest("不能邀请自己冒烟");
        }
        synchronized (lock) {
            List<SmokeSession> sessions = loadSessions();
            OnlineMember target = findOnline(targetUserId)
                    .orElseThrow(() -> ChatException.badRequest("对方不在线，无法邀请"));
            Optional<SmokeSession> targetSession = findSessionOf(sessions, targetUserId);
            if (targetSession.isPresent()
                    && SmokeSession.STATUS_ACTIVE.equals(targetSession.get().status())) {
                // 仅等待接受的人尚未真正开始，仍可被邀请；已开始的不能重复加入。
                throw conflict("对方正在进行冒烟测试");
            }

            long now = System.currentTimeMillis();
            Optional<SmokeSession> existing = findSessionOf(sessions, actor.userId());
            SmokeSession session;
            if (existing.isEmpty()) {
                session = new SmokeSession(
                        UUID.randomUUID().toString(),
                        actor.userId(),
                        actor.nickname(),
                        SmokeSession.STATUS_WAITING,
                        List.of(new SmokeSession.Participant(
                                actor.userId(), actor.nickname(), now)),
                        List.of(),
                        now,
                        now);
                sessions.add(session);
            } else {
                session = existing.get();
                if (session.pendingInvites().stream()
                        .anyMatch(invite -> invite.userId().equals(targetUserId))) {
                    throw conflict("已经邀请过对方了");
                }
            }

            List<SmokeSession.Invite> pending = new ArrayList<>(session.pendingInvites());
            pending.add(new SmokeSession.Invite(
                    target.userId(),
                    target.nickname(),
                    actor.userId(),
                    actor.nickname(),
                    now));
            replace(sessions, new SmokeSession(
                    session.id(),
                    session.initiatorUserId(),
                    session.initiatorNickname(),
                    session.status(),
                    session.participants(),
                    List.copyOf(pending),
                    session.createdAt(),
                    now));
            save(sessions);
        }
    }

    public void accept(ChatIdentity actor, String sessionId) {
        requireSessionId(sessionId);
        synchronized (lock) {
            List<SmokeSession> sessions = loadSessions();
            SmokeSession session = findById(sessions, sessionId)
                    .orElseThrow(() -> ChatException.badRequest("邀请已失效或冒烟测试已结束"));
            boolean invited = session.pendingInvites().stream()
                    .anyMatch(invite -> invite.userId().equals(actor.userId()));
            if (!invited) {
                throw ChatException.badRequest("邀请已失效");
            }
            Optional<SmokeSession> other = findSessionOf(sessions, actor.userId());
            if (other.isPresent()
                    && !other.get().id().equals(sessionId)
                    && SmokeSession.STATUS_ACTIVE.equals(other.get().status())) {
                throw conflict("你已经在其他冒烟测试中，请先结束再接受");
            }
            // 自己还在等人接受时接受了别人的邀请，自己那条等待中的会话自动作废。
            String ownWaitingId = other
                    .filter(own -> !own.id().equals(sessionId)
                            && SmokeSession.STATUS_WAITING.equals(own.status()))
                    .map(SmokeSession::id)
                    .orElse(null);

            long now = System.currentTimeMillis();
            List<SmokeSession> next = new ArrayList<>(sessions.size());
            for (SmokeSession current : sessions) {
                if (current.id().equals(ownWaitingId)) {
                    continue;
                }
                List<SmokeSession.Invite> pending = current.pendingInvites().stream()
                        .filter(invite -> !invite.userId().equals(actor.userId()))
                        .toList();
                if (current.id().equals(sessionId)) {
                    List<SmokeSession.Participant> participants =
                            new ArrayList<>(current.participants());
                    if (participants.stream()
                            .noneMatch(participant -> participant.userId().equals(actor.userId()))) {
                        participants.add(new SmokeSession.Participant(
                                actor.userId(), actor.nickname(), now));
                    }
                    next.add(new SmokeSession(
                            current.id(),
                            current.initiatorUserId(),
                            current.initiatorNickname(),
                            SmokeSession.STATUS_ACTIVE,
                            List.copyOf(participants),
                            List.copyOf(pending),
                            current.createdAt(),
                            now));
                } else if (pending.size() != current.pendingInvites().size()) {
                    // 同一个人接受了一条邀请后，其他会话里的邀请自动作废；
                    // 若该会话还在等待且已无任何邀请，则直接结束。
                    if (pending.isEmpty()
                            && SmokeSession.STATUS_WAITING.equals(current.status())) {
                        continue;
                    }
                    next.add(new SmokeSession(
                            current.id(),
                            current.initiatorUserId(),
                            current.initiatorNickname(),
                            current.status(),
                            current.participants(),
                            List.copyOf(pending),
                            current.createdAt(),
                            now));
                } else {
                    next.add(current);
                }
            }
            save(next);
        }
    }

    public void decline(ChatIdentity actor, String sessionId) {
        requireSessionId(sessionId);
        synchronized (lock) {
            List<SmokeSession> sessions = loadSessions();
            SmokeSession session = findById(sessions, sessionId)
                    .orElseThrow(() -> ChatException.badRequest("邀请已失效或冒烟测试已结束"));
            boolean invited = session.pendingInvites().stream()
                    .anyMatch(invite -> invite.userId().equals(actor.userId()));
            if (!invited) {
                throw ChatException.badRequest("邀请已失效");
            }
            List<SmokeSession.Invite> pending = session.pendingInvites().stream()
                    .filter(invite -> !invite.userId().equals(actor.userId()))
                    .toList();
            if (pending.isEmpty()
                    && SmokeSession.STATUS_WAITING.equals(session.status())) {
                // 发起人还没有等到任何人，最后一条邀请被拒绝后直接结束等待。
                sessions.removeIf(current -> current.id().equals(sessionId));
            } else {
                replace(sessions, new SmokeSession(
                        session.id(),
                        session.initiatorUserId(),
                        session.initiatorNickname(),
                        session.status(),
                        session.participants(),
                        List.copyOf(pending),
                        session.createdAt(),
                        System.currentTimeMillis()));
            }
            save(sessions);
        }
    }

    public void leave(ChatIdentity actor) {
        synchronized (lock) {
            List<SmokeSession> sessions = loadSessions();
            SmokeSession session = findSessionOf(sessions, actor.userId())
                    .orElseThrow(() -> ChatException.badRequest("你不在冒烟测试中"));
            if (session.initiatorUserId().equals(actor.userId())) {
                sessions.removeIf(current -> current.id().equals(session.id()));
            } else {
                List<SmokeSession.Participant> participants = session.participants().stream()
                        .filter(participant -> !participant.userId().equals(actor.userId()))
                        .toList();
                if (participants.size() <= 1) {
                    // 只剩发起人时结束会话，避免“一个人的冒烟测试”。
                    sessions.removeIf(current -> current.id().equals(session.id()));
                } else {
                    replace(sessions, new SmokeSession(
                            session.id(),
                            session.initiatorUserId(),
                            session.initiatorNickname(),
                            session.status(),
                            participants,
                            session.pendingInvites(),
                            session.createdAt(),
                            System.currentTimeMillis()));
                }
            }
            save(sessions);
        }
    }

    public void end(ChatIdentity actor) {
        synchronized (lock) {
            List<SmokeSession> sessions = loadSessions();
            SmokeSession session = sessions.stream()
                    .filter(current -> current.initiatorUserId().equals(actor.userId()))
                    .findFirst()
                    .orElseThrow(() -> ChatException.badRequest("没有可结束的冒烟测试"));
            sessions.removeIf(current -> current.id().equals(session.id()));
            save(sessions);
        }
    }

    public void sendState(ChatSession session) {
        registry.send(
                session,
                Map.of("type", "smoke_state", "state", stateFor(session.getUserId())));
    }

    /** Redis 广播事件到达后，把最新的个性化快照推给本节点所有 WebSocket。 */
    public void broadcastLocal() {
        List<SmokeSession> sessions = loadSessions();
        List<OnlineMember> online = chatStore.onlineMembers();
        for (ChatSession session : registry.all()) {
            registry.send(
                    session,
                    Map.of(
                            "type", "smoke_state",
                            "state", buildState(sessions, online, session.getUserId())));
        }
    }

    public void publishState() {
        chatStore.publish(ChatEvent.smoke());
    }

    private SmokeStateView buildState(
            List<SmokeSession> sessions,
            List<OnlineMember> online,
            String userId) {
        List<SmokeStateView.SmokingMember> smoking = new ArrayList<>();
        for (SmokeSession session : sessions) {
            if (!SmokeSession.STATUS_ACTIVE.equals(session.status())) {
                continue;
            }
            for (SmokeSession.Participant participant : session.participants()) {
                smoking.add(new SmokeStateView.SmokingMember(
                        session.id(),
                        participant.userId(),
                        participant.nickname(),
                        participant.userId().equals(session.initiatorUserId())));
            }
        }

        List<SmokeStateView.Invite> invites = new ArrayList<>();
        for (SmokeSession session : sessions) {
            for (SmokeSession.Invite invite : session.pendingInvites()) {
                if (invite.userId().equals(userId)) {
                    invites.add(new SmokeStateView.Invite(
                            session.id(),
                            invite.invitedByUserId(),
                            invite.invitedByNickname(),
                            invite.invitedAt()));
                }
            }
        }

        SmokeStateView.Session mine = sessions.stream()
                .filter(session -> hasParticipant(session, userId))
                .findFirst()
                .map(this::toView)
                .orElse(null);
        return new SmokeStateView(smoking.size(), smoking, online, invites, mine);
    }

    private SmokeStateView.Session toView(SmokeSession session) {
        return new SmokeStateView.Session(
                session.id(),
                session.status(),
                session.initiatorUserId(),
                session.initiatorNickname(),
                session.participants().stream()
                        .map(participant -> new SmokeStateView.Participant(
                                participant.userId(),
                                participant.nickname(),
                                participant.joinedAt()))
                        .toList(),
                session.pendingInvites().stream()
                        .map(invite -> new SmokeStateView.PendingInvite(
                                invite.userId(),
                                invite.nickname(),
                                invite.invitedAt()))
                        .toList());
    }

    private List<SmokeSession> loadSessions() {
        long cutoff = System.currentTimeMillis() - MAX_SESSION_AGE_MS;
        List<SmokeSession> sessions = new ArrayList<>();
        for (SmokeSession session : smokeStore.load()) {
            if (session == null
                    || session.id() == null
                    || session.participants() == null
                    || session.participants().isEmpty()
                    || session.updatedAt() < cutoff) {
                continue;
            }
            sessions.add(session);
        }
        return sessions;
    }

    private void save(List<SmokeSession> sessions) {
        smokeStore.save(sessions);
        chatStore.publish(ChatEvent.smoke());
    }

    private Optional<OnlineMember> findOnline(String userId) {
        return chatStore.onlineMembers().stream()
                .filter(member -> member.userId().equals(userId))
                .findFirst();
    }

    private static Optional<SmokeSession> findSessionOf(
            List<SmokeSession> sessions, String userId) {
        return sessions.stream()
                .filter(session -> hasParticipant(session, userId))
                .findFirst();
    }

    private static Optional<SmokeSession> findById(
            List<SmokeSession> sessions, String sessionId) {
        return sessions.stream()
                .filter(session -> session.id().equals(sessionId))
                .findFirst();
    }

    private static boolean hasParticipant(SmokeSession session, String userId) {
        return session.participants().stream()
                .anyMatch(participant -> participant.userId().equals(userId));
    }

    private static void replace(List<SmokeSession> sessions, SmokeSession updated) {
        sessions.replaceAll(session ->
                session.id().equals(updated.id()) ? updated : session);
    }

    private static void requireSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw ChatException.badRequest("缺少冒烟测试会话");
        }
    }

    private static ChatException conflict(String message) {
        return new ChatException(HttpStatus.CONFLICT, "SMOKE_CONFLICT", message);
    }
}
