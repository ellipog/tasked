package dev.ellipog.tenet.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;

/**
 * The presses this client has asked for and not yet had answered, and when to stop believing them.
 *
 * <h2>Why a store with a clock rather than a set of keys</h2>
 *
 * <p>This was a {@code Set<String>} on the screen, cleared wholesale whenever the progress revision moved.
 * That is a set with no idea what it is holding: <b>any</b> progress message emptied it — a teammate's claim,
 * another quest's task tick, or the once-a-second condition refresh — so a row the player had just pressed
 * went back to "ready" mid-flight and then collected again when its own answer arrived. The player saw a
 * claim that flickered, and the flicker was the client forgetting a press it had not yet heard about.
 *
 * <p>So a mark is a key <i>and</i> the moment it was made, and it goes for one of exactly three reasons:
 * the server's answer agreed with it, the thing it named is no longer in the tree, or it has been believed
 * for longer than {@link #BACKSTOP_MILLIS} without an answer. Nothing else forgets a mark, and the third
 * reason is the one that has to be <b>said out loud</b> — a value that quietly reverts after a few seconds
 * is indistinguishable from a bug.
 *
 * <h2>Why the arithmetic lives here</h2>
 *
 * <p>{@link #outstanding} is the rule "how much is still owed once this player's own presses are taken off
 * the count". It is a pure function of two predicates, so {@code PendingClaimsTest} can assert the whole
 * cross-product — and it has to be, because the screen that reads it cannot be instantiated by a test.
 * Every count in the book goes through it, which is what stops a row reading collected while the badge
 * above it still counts it.
 */
public final class PendingClaims {

    /**
     * How long a mark is believed without an answer, in milliseconds.
     *
     * <h2>Why this is short, and why it is not zero</h2>
     *
     * <p>A claim always answers: every claim handler sends a progress sync whether or not it paid, so a
     * refusal reaches the client and the mark goes by agreement. A <b>hand-in does not</b> — the submit
     * handler sends nothing when nothing changed — so for that one path this backstop is the only thing
     * that ever puts the row back.
     *
     * <p>Which is why it is three seconds rather than thirty: a refused hand-in draws "handed in" for this
     * long, and that is the whole cost of not carrying an acknowledgement on the wire. It is also why the
     * expiry is reported rather than silent — see the class note.
     */
    public static final long BACKSTOP_MILLIS = 3000L;

    /** One mark: when it was made. */
    private record Mark(long at) {
    }

    /** The marks in flight, oldest first. A {@code LinkedHashMap} so a report names them in press order. */
    private final Map<String, Mark> marks = new LinkedHashMap<>();

    /**
     * Which set of marks this is.
     *
     * <h2>Why a counter and not the size</h2>
     *
     * <p>Because two different sets can hold the same number of marks, and a reader that cached its answer
     * against the count would go on answering from the previous set — the same argument
     * {@code ClientTableReplica.generation} makes, and the same fault it was written for. It moves on
     * every path that changes what a reader would see: a mark, a resolve and an expiry.
     */
    private long revision;

    /**
     * The key a hand-in is marked under.
     *
     * <p>A task's own key rather than the quest's, because a quest holds several tasks and handing one in
     * is not handing in the rest. The shape is {@code ObservationWatcher}'s, which keys the tasks it
     * watches the same way.
     */
    public static String taskKey(String questId, int taskIndex) {
        return "task:" + questId + "#" + taskIndex;
    }

    /** The key a whole quest's rewards are marked under. */
    public static String questKey(String questId) {
        return RewardInboxLayout.questKey(questId);
    }

    /** The key one reward is marked under. */
    public static String rewardKey(String questId, int rewardIndex) {
        return RewardInboxLayout.rewardKey(questId, rewardIndex);
    }

    /** The key a chapter's rewards are marked under. */
    public static String chapterKey(String chapterId) {
        return RewardInboxLayout.chapterKey(chapterId);
    }

    /** What a mark is about. */
    public enum Kind {
        /** One task of a quest, handed in by hand or by watching. */
        TASK,
        /** One reward of a quest. */
        REWARD,
        /** A whole quest's rewards. */
        QUEST,
        /** A whole chapter's rewards. */
        CHAPTER
    }

    /**
     * What a key names: which kind of thing, whose, and which one.
     *
     * <h2>Why the keys are parsed rather than kept as fields</h2>
     *
     * <p>Because a mark has to survive the thing it names leaving the tree, and the reconciler's question
     * is "does this key still mean anything" — which it can only ask of the key. Parsing in one place, with
     * a test, is what keeps that from becoming a {@code startsWith} ladder in the screen. The separators
     * are the ones {@link RewardInboxLayout} already builds its row keys from, so a slot lookup and this
     * cannot disagree about where an id ends and an index begins.
     *
     * <p>Null for a key this build cannot read. The caller forgets it rather than believing it, which is
     * the safe direction: a mark nobody can interpret is a mark nobody can resolve.
     */
    public static Subject subjectOf(String key) {
        if (key == null) {
            return null;
        }
        try {
            if (key.startsWith("task:")) {
                int hash = key.lastIndexOf('#');
                return hash < 0 ? null : new Subject(Kind.TASK, key.substring(5, hash),
                        Integer.parseInt(key.substring(hash + 1)));
            }
            if (key.startsWith("quest:")) {
                return new Subject(Kind.QUEST, key.substring(6), -1);
            }
            if (key.startsWith("chapter:")) {
                return new Subject(Kind.CHAPTER, key.substring(8), -1);
            }
            if (key.startsWith("reward:")) {
                int colon = key.lastIndexOf(':');
                return colon < 0 ? null : new Subject(Kind.REWARD, key.substring(7, colon),
                        Integer.parseInt(key.substring(colon + 1)));
            }
        }
        catch (NumberFormatException notAKeyThisBuildWrote) {
            return null;
        }
        return null;
    }

    /**
     * A key, taken apart.
     *
     * @param kind  what kind of thing it names
     * @param owner the quest or chapter id it belongs to
     * @param index the task's or reward's position, or -1 for a whole quest or chapter
     */
    public record Subject(Kind kind, String owner, int index) {
    }

    /**
     * Records a press, and answers whether it was new.
     *
     * <p><b>False means do not send.</b> A press for this key is already in flight, and a second one would
     * be a second packet the server has to refuse — the same contract {@code ClientEditReplies.noteSent}
     * keeps, and for the same reason: the caller that ignores it is the caller that sends twice.
     */
    public boolean mark(String key, long nowMillis) {
        if (key == null || key.isEmpty() || marks.containsKey(key)) {
            return false;
        }
        marks.put(key, new Mark(nowMillis));
        revision++;
        return true;
    }

    /** Whether a press for this key is in flight. */
    public boolean isMarked(String key) {
        return key != null && marks.containsKey(key);
    }

    /** Which set of marks this is. See {@link #revision}. */
    public long revision() {
        return revision;
    }

    /** Every key in flight, oldest first, as a copy — the walk the reconciler makes. */
    public List<String> keys() {
        return new ArrayList<>(marks.keySet());
    }

    /** Forgets one key because the server's answer agreed with it, or because its subject is gone. */
    public void resolve(String key) {
        if (key != null && marks.remove(key) != null) {
            revision++;
        }
    }

    /**
     * Forgets every mark older than the backstop, and answers which those were.
     *
     * <p>Answers them rather than only dropping them, because the caller has to say so: a row that reverts
     * with no sentence is the failure this whole class exists to remove. A clock that has gone backwards
     * expires nothing, which is the safe direction — a mark kept too long is corrected by the next answer.
     */
    public List<String> expired(long nowMillis) {
        List<String> out = new ArrayList<>();
        for (java.util.Iterator<Map.Entry<String, Mark>> each = marks.entrySet().iterator();
                each.hasNext(); ) {
            Map.Entry<String, Mark> entry = each.next();
            if (nowMillis - entry.getValue().at() >= BACKSTOP_MILLIS) {
                out.add(entry.getKey());
                each.remove();
            }
        }
        if (!out.isEmpty()) {
            revision++;
        }
        return out;
    }

    /**
     * How many of a quest's rewards are still owed once this player's own presses are taken off the count.
     *
     * <h2>Why the answer is "owed", not "listed"</h2>
     *
     * <p>An optimistic row <b>stays on screen</b> — it has to, or the list would change shape under the
     * pointer and a refusal would make it reappear, which reads as a press that did nothing. So the row
     * list and the counts answer different questions: the list shows what the player was looking at, and
     * this counts what is still to be taken. A badge that counted the listed rows would say "3 ready"
     * above three rows that all read collected.
     *
     * @param rewards   how many rewards the quest defines
     * @param claimable whether the server says this player could take reward {@code i}
     * @param marked    whether a press is in flight for reward {@code i}
     */
    public static int outstanding(int rewards, IntPredicate claimable, IntPredicate marked) {
        int owed = 0;
        for (int index = 0; index < rewards; index++) {
            if (claimable.test(index) && !marked.test(index)) {
                owed++;
            }
        }
        return owed;
    }
}
