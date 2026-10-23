package com.fishmatrix.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fishmatrix.chat.model.ChatException;
import com.fishmatrix.chat.model.ChatIdentity;
import com.fishmatrix.chat.model.OnlineMember;
import com.fishmatrix.chat.model.SmokeStateView;
import com.fishmatrix.chat.store.SmokeSession;
import com.fishmatrix.chat.store.SmokeStore;
import com.fishmatrix.chat.ws.ChatSessionRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SmokeServiceTest {

    private static final ChatIdentity ALICE = ChatIdentity.of(1, "甲", "USER");
    private static final ChatIdentity BOB = ChatIdentity.of(2, "乙", "USER");
    private static final ChatIdentity CAROL = ChatIdentity.of(3, "丙", "USER");

    private FakeSmokeStore smokeStore;
    private FakeChatStore chatStore;
    private SmokeService service;

    @BeforeEach
    void setUp() {
        smokeStore = new FakeSmokeStore();
        chatStore = new FakeChatStore();
        service = new SmokeService(smokeStore, chatStore, mock(ChatSessionRegistry.class));
        online(ALICE);
        online(BOB);
        online(CAROL);
    }

    @Test
    void inviteWaitsUntilTargetAccepts() {
        service.invite(ALICE, "u2");

        SmokeStateView alice = service.stateFor("u1");
        assertThat(alice.count()).isZero();
        assertThat(alice.mySession()).isNotNull();
        assertThat(alice.mySession().status()).isEqualTo(SmokeSession.STATUS_WAITING);
        assertThat(alice.mySession().pendingInvites())
                .extracting(SmokeStateView.PendingInvite::userId)
                .containsExactly("u2");

        SmokeStateView bob = service.stateFor("u2");
        assertThat(bob.invites()).singleElement().satisfies(invite -> {
            assertThat(invite.sessionId()).isEqualTo(alice.mySession().id());
            assertThat(invite.fromUserId()).isEqualTo("u1");
        });

        service.accept(BOB, alice.mySession().id());

        SmokeStateView after = service.stateFor("u1");
        assertThat(after.count()).isEqualTo(2);
        assertThat(after.mySession().status()).isEqualTo(SmokeSession.STATUS_ACTIVE);
        assertThat(after.smoking()).extracting(SmokeStateView.SmokingMember::userId)
                .containsExactlyInAnyOrder("u1", "u2");
        assertThat(service.stateFor("u3").smoking()).hasSize(2);
    }

    @Test
    void initiatorCanInviteMorePeopleIntoActiveSession() {
        service.invite(ALICE, "u2");
        String sessionId = service.stateFor("u1").mySession().id();
        service.accept(BOB, sessionId);

        service.invite(ALICE, "u3");
        service.accept(CAROL, sessionId);

        assertThat(service.stateFor("u1").count()).isEqualTo(3);
        assertThat(service.stateFor("u1").mySession().participants())
                .extracting(SmokeStateView.Participant::userId)
                .containsExactly("u1", "u2", "u3");
    }

    @Test
    void lastDeclineRemovesWaitingSession() {
        service.invite(ALICE, "u2");
        String sessionId = service.stateFor("u1").mySession().id();

        service.decline(BOB, sessionId);

        assertThat(smokeStore.load()).isEmpty();
        assertThat(service.stateFor("u1").mySession()).isNull();
        assertThat(service.stateFor("u2").invites()).isEmpty();
    }

    @Test
    void rejectInviteForOfflineOrSelf() {
        assertThatThrownBy(() -> service.invite(ALICE, "u1"))
                .isInstanceOf(ChatException.class)
                .hasMessageContaining("自己");
        assertThatThrownBy(() -> service.invite(ALICE, "u99"))
                .isInstanceOf(ChatException.class)
                .hasMessageContaining("不在线");
    }

    @Test
    void expiredInviteCannotBeAcceptedAgain() {
        service.invite(ALICE, "u2");
        service.invite(CAROL, "u2");

        String aliceSession = service.stateFor("u1").mySession().id();
        String carolSession = service.stateFor("u3").mySession().id();
        service.accept(BOB, aliceSession);

        assertThatThrownBy(() -> service.accept(BOB, carolSession))
                .isInstanceOf(ChatException.class)
                .hasMessageContaining("失效");
    }

    @Test
    void acceptingOneInviteDropsTheOthers() {
        service.invite(ALICE, "u2");
        service.invite(CAROL, "u2");
        String aliceSession = service.stateFor("u1").mySession().id();

        service.accept(BOB, aliceSession);

        assertThat(service.stateFor("u2").invites()).isEmpty();
        assertThat(smokeStore.load())
                .filteredOn(session -> !session.id().equals(aliceSession))
                .allSatisfy(session -> assertThat(session.pendingInvites()).isEmpty());
    }

    @Test
    void waitingInitiatorCanAcceptAnotherInvite() {
        service.invite(ALICE, "u2");
        service.invite(BOB, "u3");
        String aliceSession = service.stateFor("u1").mySession().id();
        String bobWaitingSession = service.stateFor("u2").mySession().id();

        service.accept(BOB, aliceSession);

        assertThat(service.stateFor("u2").mySession().id()).isEqualTo(aliceSession);
        assertThat(smokeStore.load())
                .extracting(SmokeSession::id)
                .doesNotContain(bobWaitingSession);
        assertThat(service.stateFor("u3").invites()).isEmpty();
    }

    @Test
    void participantLeavesAndSessionClosesWhenAlone() {
        service.invite(ALICE, "u2");
        String sessionId = service.stateFor("u1").mySession().id();
        service.accept(BOB, sessionId);

        service.leave(BOB);

        assertThat(service.stateFor("u1").mySession()).isNull();
        assertThat(service.stateFor("u1").count()).isZero();
    }

    @Test
    void participantLeaveKeepsSessionWithRemainingMembers() {
        service.invite(ALICE, "u2");
        String sessionId = service.stateFor("u1").mySession().id();
        service.accept(BOB, sessionId);
        service.invite(ALICE, "u3");
        service.accept(CAROL, sessionId);

        service.leave(BOB);

        SmokeStateView state = service.stateFor("u1");
        assertThat(state.count()).isEqualTo(2);
        assertThat(state.mySession().participants())
                .extracting(SmokeStateView.Participant::userId)
                .containsExactly("u1", "u3");
    }

    @Test
    void onlyInitiatorCanEndSession() {
        service.invite(ALICE, "u2");
        String sessionId = service.stateFor("u1").mySession().id();
        service.accept(BOB, sessionId);

        assertThatThrownBy(() -> service.end(BOB))
                .isInstanceOf(ChatException.class)
                .hasMessageContaining("没有可结束");

        service.end(ALICE);
        assertThat(smokeStore.load()).isEmpty();
        assertThat(service.stateFor("u2").mySession()).isNull();
    }

    @Test
    void publishesSmokeEventOnEveryChange() {
        service.invite(ALICE, "u2");
        String sessionId = service.stateFor("u1").mySession().id();
        service.accept(BOB, sessionId);
        service.end(ALICE);

        assertThat(chatStore.events)
                .filteredOn(event -> "smoke".equals(event.type()))
                .hasSize(3);
    }

    private void online(ChatIdentity identity) {
        chatStore.markOnline(
                new OnlineMember(null, identity.userId(), identity.nickname(), "node-1",
                        System.currentTimeMillis()),
                60_000);
    }

    private static final class FakeSmokeStore implements SmokeStore {

        private List<SmokeSession> sessions = new ArrayList<>();

        @Override
        public List<SmokeSession> load() {
            return List.copyOf(sessions);
        }

        @Override
        public void save(List<SmokeSession> sessions) {
            this.sessions = new ArrayList<>(sessions);
        }
    }
}
