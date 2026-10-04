package dev.ellipog.tasked.client;

import dev.ellipog.tasked.client.QuestNotifications.Kind;
import dev.ellipog.tasked.client.QuestNotifications.Notice;
import dev.ellipog.tasked.client.QuestNotifications.Snapshot;
import dev.ellipog.tasked.progress.QuestState;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The completion and claim diff, without a client.
 *
 * <p>The interesting cases are the silences: a join must not announce the finished half of the book,
 * a cleared cache must not read as a hundred removals, and a silent auto-claim mode must not speak.
 * Each is a rule the class documents, and each is asserted here rather than trusted.
 */
@DisplayName("the notice diff: what just happened, and the four ways it says nothing")
class QuestNotificationsTest {

    private final QuestNotifications diff = new QuestNotifications();

    private static Snapshot quest(String id, QuestState state) {
        return new Snapshot(id, state, false, false);
    }

    private static Snapshot claimable(String id) {
        return new Snapshot(id, QuestState.COMPLETED, true, false);
    }

    @Nested
    @DisplayName("completions")
    class Completions {

        @Test
        @DisplayName("the first sample seeds and says nothing, however finished the book is")
        void aJoinIsSilent() {
            assertEquals(List.of(), diff.sample(List.of(
                    quest("a", QuestState.COMPLETED),
                    quest("b", QuestState.STARTED))));
        }

        @Test
        @DisplayName("a quest that becomes complete announces once, and only once")
        void aCompletionAnnouncesOnce() {
            diff.sample(List.of(quest("a", QuestState.STARTED)));

            List<Notice> notices = diff.sample(List.of(quest("a", QuestState.COMPLETED)));
            assertEquals(List.of(new Notice(Kind.COMPLETED, "a")), notices);

            // And the same state again is not a second event: a sync repeats, a completion does not.
            assertEquals(List.of(), diff.sample(List.of(quest("a", QuestState.COMPLETED))));
        }

        @Test
        @DisplayName("a repeatable quest completing again announces again")
        void aRepeatAnnouncesAgain() {
            diff.sample(List.of(quest("a", QuestState.STARTED)));
            diff.sample(List.of(quest("a", QuestState.COMPLETED)));

            // The server puts a repeatable quest back in progress for the next round.
            diff.sample(List.of(quest("a", QuestState.UNLOCKED)));
            assertEquals(List.of(new Notice(Kind.COMPLETED, "a")),
                    diff.sample(List.of(quest("a", QuestState.COMPLETED))));
        }

        @Test
        @DisplayName("a silent auto-claim mode suppresses the completion")
        void silentModesDoNotSpeak() {
            diff.sample(List.of(new Snapshot("a", QuestState.STARTED, false, false)));

            assertEquals(List.of(), diff.sample(List.of(
                    new Snapshot("a", QuestState.COMPLETED, false, true))));
        }

        @Test
        @DisplayName("a quest that drops out of the tree is forgotten, not kept")
        void aRemovedQuestIsForgotten() {
            diff.sample(List.of(quest("a", QuestState.STARTED)));
            diff.sample(List.of());   // the tree moved and 'a' is gone
            diff.sample(List.of(quest("a", QuestState.STARTED)));   // and comes back

            // It reappears in progress and then completes: the notice is about the completion, not
            // about a remembered state from before the removal.
            assertEquals(List.of(new Notice(Kind.COMPLETED, "a")),
                    diff.sample(List.of(quest("a", QuestState.COMPLETED))));
        }
    }

    @Nested
    @DisplayName("claims")
    class Claims {

        @Test
        @DisplayName("waiting rewards that stop waiting are a claim, announced once")
        void aClaimAnnounces() {
            diff.sample(List.of(claimable("a")));

            assertEquals(List.of(new Notice(Kind.CLAIMED, "a")),
                    diff.sample(List.of(quest("a", QuestState.COMPLETED))));
            assertEquals(List.of(), diff.sample(List.of(quest("a", QuestState.COMPLETED))));
        }

        @Test
        @DisplayName("a completion is not a claim, even when it pays out")
        void aCompletionIsNotAClaim() {
            // A quest that completes with its rewards auto-granted was never claimable at either
            // sample: the completion is the event, and the claim sound does not fire for it.
            diff.sample(List.of(quest("a", QuestState.STARTED)));
            assertEquals(List.of(new Notice(Kind.COMPLETED, "a")),
                    diff.sample(List.of(quest("a", QuestState.COMPLETED))));
        }

        @Test
        @DisplayName("a reset is neither a claim nor a completion")
        void aResetIsSilent() {
            diff.sample(List.of(claimable("a")));
            assertEquals(List.of(), diff.sample(List.of(quest("a", QuestState.LOCKED))));
        }
    }

    @Nested
    @DisplayName("the reset rule")
    class Resets {

        @Test
        @DisplayName("an empty sample forgets everything, so the next one seeds silently")
        void anEmptySampleResets() {
            diff.sample(List.of(quest("a", QuestState.STARTED)));
            assertEquals(List.of(), diff.sample(List.of()));

            // A world change: the same id arrives complete on the next server, and that is a join,
            // not a completion that happened in front of this player.
            assertEquals(List.of(), diff.sample(List.of(quest("a", QuestState.COMPLETED))));
        }

        @Test
        @DisplayName("reset() does the same, for the disconnect hook")
        void resetForgets() {
            diff.sample(List.of(quest("a", QuestState.STARTED)));
            diff.reset();
            assertEquals(List.of(), diff.sample(List.of(quest("a", QuestState.COMPLETED))));
        }

        @Test
        @DisplayName("a quest entering the tree part-way through is seeded, not announced")
        void aNewQuestIsSeeded() {
            diff.sample(List.of(quest("a", QuestState.STARTED)));
            // 'b' appears already complete -- a reload added it, or the tree gained a chapter.
            List<Notice> notices = diff.sample(List.of(
                    quest("a", QuestState.STARTED), quest("b", QuestState.COMPLETED)));
            assertTrue(notices.isEmpty(), "a quest first seen complete is not a completion");
        }
    }
}
