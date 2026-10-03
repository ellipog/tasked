package dev.ellipog.tasked.client;

import dev.ellipog.armature.api.net.ArmatureNetwork;
import dev.ellipog.tasked.net.SubmitTaskPayload;
import dev.ellipog.tasked.progress.QuestState;
import dev.ellipog.tasked.quest.task.ObservationTask;

import net.minecraft.client.Minecraft;

import java.util.HashMap;
import java.util.Map;

/**
 * Watches the crosshair for observation tasks and submits when one has been looked at long enough.
 *
 * <h2>Why this is the client's job, and the only one</h2>
 *
 * <p>A server never sees where a player is looking, so an observation task can only be judged where
 * the eyes are. FTB Quests does the same thing, and the trust is deliberate and bounded: the worst a
 * modified client can do is finish an observation without watching, which grants nothing by itself.
 * Everything a payout depends on is still decided server-side.
 *
 * <p>Called once per client tick from each loader's tick hook — the only two seams that exist, since
 * Armature has no client-tick event. The counters are per session and in memory: what the server keeps
 * is the completed task, recorded in the ordinary progress record.
 */
public final class ObservationWatcher {

    private ObservationWatcher() {
    }

    /** How many consecutive ticks each observation task has been watched for, keyed quest#task. */
    private static final Map<String, Integer> HELD = new HashMap<>();

    /** One client tick: what is under the crosshair, and for how long it has been there. */
    public static void tick(Minecraft minecraft) {
        if (minecraft == null || minecraft.player == null || minecraft.level == null) {
            HELD.clear();
            return;
        }
        if (minecraft.screen != null || minecraft.hitResult == null) {
            // A menu open means the crosshair is stale; looking away is not watching, and the count
            // restarts rather than being kept warm.
            HELD.clear();
            return;
        }

        for (ClientQuestCache.Entry entry : ClientQuestCache.entries()) {
            if (ClientQuestCache.stateOf(entry.id()) == QuestState.COMPLETED) {
                // Finished quests have nothing to submit for; skipping them keeps the loop over the
                // whole book cheap on a client with hundreds of quests.
                continue;
            }
            for (int index = 0; index < entry.tasks().size(); index++) {
                ClientQuestCache.TaskEntry task = entry.tasks().get(index);
                ObservationTask.ObserveType kind = task.observation();
                if (kind == null || ClientQuestCache.taskProgressOf(entry.id(), index) >= 1) {
                    continue;
                }
                String key = entry.id() + "#" + index;
                if (!ObservationTask.matches(minecraft.hitResult, minecraft.level, kind,
                        task.observeTarget())) {
                    HELD.remove(key);
                    continue;
                }
                int held = HELD.merge(key, 1, Integer::sum);
                if (held >= Math.max(1, task.observeTicks())) {
                    HELD.remove(key);
                    // The server accepts this because the type says it may; see TaskBehaviour and
                    // ProgressService.submit. No button is involved.
                    ArmatureNetwork.sendToServer(new SubmitTaskPayload(entry.id(), index));
                }
            }
        }
    }

    /** Forgets every counter. For a disconnect: another server's tasks are not this one's. */
    public static void reset() {
        HELD.clear();
    }
}
