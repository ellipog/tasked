package dev.ellipog.tasked.progress;

import dev.ellipog.tasked.Constants;
import dev.ellipog.tasked.Tasked;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Every team's quest progress, for one world.
 *
 * <h2>Keyed by team id, which is a player's UUID when they are alone</h2>
 *
 * <p>Armature gives a solo player a team whose id <b>is their own UUID</b> (see {@code Team.solo}).
 * That single decision makes this class simple: progress is always looked up by "the id of the team
 * you are in", with no branch for solo players and no second storage layout.
 *
 * <p>It also gives the right behaviour for free when somebody joins a party. A player's solo progress
 * sits under their UUID; the team they join has its own id and its own progress. Joining means they
 * now read the team's — and their own is untouched, waiting if they leave. FTB Quests behaves the
 * same way, and the alternative (merging, or importing) is a decision nobody can make well
 * automatically: a new member arriving with four hundred completed quests would hand the whole party
 * a finished questline.
 *
 * <h2>Saved per world</h2>
 *
 * <p>A {@link SavedData} on the overworld rather than per-dimension, so a quest finished in the
 * Nether counts. Marked dirty after every write; forgetting that produces progress that works all
 * session and is gone tomorrow, which is a miserable thing to be handed.
 */
public final class ProgressStore extends SavedData {

    private static final String DATA_NAME = "tasked_progress";

    private final Map<UUID, TeamProgress> byTeam = new LinkedHashMap<>();

    /** The store for this server. */
    public static ProgressStore of(MinecraftServer server) {
        // Overworld, so the data is global rather than per-dimension. Verified by javap against
        // the actual 1.21.1 artefact (see .utils/find_savedata.py): SavedData.Factory in this
        // version takes THREE arguments -- the constructor, the loader, and a nullable
        // DataFixTypes. Two arguments is the older shape and does not compile.
        //
        // The null is deliberate: DataFixTypes is the vanilla datafixer's migration table, and
        // this is our own format with our own version key, so there is nothing for it to migrate.
        return server.overworld().getDataStorage()
                .computeIfAbsent(new SavedData.Factory<>(ProgressStore::new, ProgressStore::load, null), DATA_NAME);
    }

    /** The progress of one team. Never null — an unknown team has no completed quests, which is a valid state. */
    public TeamProgress progressOf(UUID teamId) {
        return byTeam.getOrDefault(teamId, TeamProgress.empty());
    }

    public boolean has(UUID teamId) {
        return byTeam.containsKey(teamId);
    }

    /** Replaces a team's progress and marks the store for saving. */
    public void put(UUID teamId, TeamProgress progress) {
        byTeam.put(teamId, progress);
        setDirty();
    }

    /** Forgets a team entirely. Used by {@code /tasked reset}, not by leaving a party. */
    public void clear(UUID teamId) {
        byTeam.remove(teamId);
        setDirty();
    }

    public int teamCount() {
        return byTeam.size();
    }

    // ------------------------------------------------------------------

    static ProgressStore load(CompoundTag tag, HolderLookup.Provider registries) {
        ProgressStore store = new ProgressStore();

        ListTag list = tag.getList("teams", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            UUID teamId = readUuid(entry.getString("team"));
            if (teamId == null) {
                Constants.LOG.warn("tasked: skipping stored progress with no team id");
                continue;
            }
            store.byTeam.put(teamId, TeamProgress.load(entry, registries));
        }

        return store;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("version", 1);

        ListTag list = new ListTag();
        byTeam.forEach((teamId, progress) -> {
            CompoundTag entry = new CompoundTag();
            // As a string rather than putUUID: greppable when someone opens the file to debug, and
            // getUUID returns null for a missing key rather than failing where the problem is.
            entry.putString("team", teamId.toString());
            progress.save(entry);
            list.add(entry);
        });
        tag.put("teams", list);

        return tag;
    }

    private static UUID readUuid(String raw) {
        if (raw.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        }
        catch (IllegalArgumentException e) {
            Constants.LOG.warn("tasked: '{}' is not a valid team uuid in stored progress", raw);
            return null;
        }
    }
}
