package dev.ellipog.tasked.client;

import dev.ellipog.tasked.client.QuestNotifications.Kind;
import dev.ellipog.tasked.client.QuestNotifications.Notice;
import dev.ellipog.tasked.client.QuestNotifications.Snapshot;
import dev.ellipog.tasked.progress.QuestState;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

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

    /**
     * The partial sample, which is what a delta is read with.
     *
     * <h2>Why these are separate cases rather than a shorter list</h2>
     *
     * <p>A delta names the quests that moved. Reading it with the whole-cache method would <b>forget
     * every quest it did not name</b> — and this diff only announces a transition it has a previous
     * sample for, so a forgotten quest can never be announced again. That is the failure these cases
     * exist to catch, and it is silent: no exception, no log line, just a completion that never makes
     * a sound.
     */
    @Nested
    @DisplayName("the partial sample a delta is read with")
    class Partial {

        @Test
        @DisplayName("a quest the delta named announces its completion")
        void aNamedQuestAnnounces() {
            diff.sample(List.of(quest("a", QuestState.STARTED), quest("b", QuestState.STARTED)));

            assertEquals(List.of(new Notice(Kind.COMPLETED, "a")),
                    diff.sampleSome(Set.of("a"), List.of(quest("a", QuestState.COMPLETED))));
        }

        @Test
        @DisplayName("a quest the delta did not name keeps the sample it had")
        void anUnnamedQuestKeepsItsBaseline() {
            diff.sample(List.of(quest("a", QuestState.STARTED), quest("b", QuestState.STARTED)));

            // A delta about 'a' that changes nothing about it. The whole-cache method would forget 'b'
            // here, and the cost of that is the next assertion rather than this one.
            assertEquals(List.of(), diff.sampleSome(Set.of("a"), List.of(quest("a", QuestState.STARTED))));

            assertEquals(List.of(new Notice(Kind.COMPLETED, "b")),
                    diff.sampleSome(Set.of("b"), List.of(quest("b", QuestState.COMPLETED))),
                    "'b' was not named by the earlier delta, which is not the same as it being gone");
        }

        @Test
        @DisplayName("a partial sample of nothing is not a reset")
        void anEmptyPartialIsNotAReset() {
            diff.sample(List.of(quest("a", QuestState.STARTED)));

            // The full method reads an empty sample as "the cache is empty, forget everything". A delta
            // that named nothing is a message about nothing, and the baseline has to survive it.
            assertEquals(List.of(), diff.sampleSome(Set.of(), List.of()));

            assertEquals(List.of(new Notice(Kind.COMPLETED, "a")),
                    diff.sampleSome(Set.of("a"), List.of(quest("a", QuestState.COMPLETED))));
        }

        @Test
        @DisplayName("an id the message named and the tree no longer holds is forgotten")
        void aNamedButAbsentIdIsForgotten() {
            diff.sample(List.of(quest("a", QuestState.STARTED)));

            // The delta named 'a' and the tree has no picture of it: a removal, said the only way a
            // delta can say one.
            assertEquals(List.of(), diff.sampleSome(Set.of("a"), List.of()));

            assertEquals(List.of(), diff.sampleSome(Set.of("a"), List.of(quest("a", QuestState.STARTED))),
                    "it comes back and is seeded again, rather than compared with what it was before");
            assertEquals(List.of(new Notice(Kind.COMPLETED, "a")),
                    diff.sampleSome(Set.of("a"), List.of(quest("a", QuestState.COMPLETED))));
        }

        @Test
        @DisplayName("a claim is announced through a delta as well as through a full sync")
        void aClaimAnnouncesThroughADelta() {
            diff.sample(List.of(claimable("a")));

            assertEquals(List.of(new Notice(Kind.CLAIMED, "a")),
                    diff.sampleSome(Set.of("a"), List.of(quest("a", QuestState.COMPLETED))));
        }
    }
}
