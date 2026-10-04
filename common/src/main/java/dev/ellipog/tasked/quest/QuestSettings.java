package dev.ellipog.tasked.quest;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.ellipog.tasked.Constants;
import dev.ellipog.tasked.quest.reward.RewardAutoClaim;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * The quest tree's own settings, from the {@code settings} block of {@code index.json}.
 *
 * <h2>What lives here, and why it is not a chapter's business</h2>
 *
 * <p>These are the answers that belong to the whole book rather than to any chapter: how rewards
 * default to being handed over, whether they go to the team, whether all automatic claiming is
 * suppressed, how long after joining the first task check waits — and what the book calls itself and
 * the item it wears in its header. FTB Quests keeps the same set on its quest file, and pack authors
 * expect to find them in one place.
 *
 * <p>The keys are camelCase like every other Tasked field, not FTBQ's snake_case: Tasked's files are
 * Tasked's, and a format that mixes two spellings is a format nobody can remember.
 *
 * <p>Loading is deliberately lenient: a missing file, a missing block, or a value this build does not
 * know leaves the defaults in place and logs. The tree still has to load — an author with a typo in a
 * settings block should get a working book and a line in the log, not a server that refuses to start.
 * The book's icon is an item id that is resolved on the client, so an id this build cannot resolve is
 * drawn as the header's own "missing item" mark rather than refused here.
 */
public record QuestSettings(RewardAutoClaim defaultAutoClaim, boolean defaultTeamReward,
                            boolean suppressAllAutoclaiming, int detectionDelay,
                            String bookTitle, String bookIcon) {

    /**
     * What a tree that says nothing gets.
     *
     * <p>Automatic claiming is <b>off</b>, as in FTB Quests: a finished quest announcing a payout and
     * handing it over in the same breath is a choice an author makes, not one the mod makes for them.
     * An empty title and icon mean "draw the client's own title and no icon", which is what every
     * pack that predates these fields gets.
     */
    public static final QuestSettings DEFAULTS =
            new QuestSettings(RewardAutoClaim.DISABLED, false, false, 20, "", "");

    /** The field names, for the validator and the schema. */
    public static final Set<String> FIELDS =
            Set.of("defaultAutoClaim", "defaultTeamReward", "suppressAllAutoclaiming", "detectionDelay",
                    "bookTitle", "bookIcon");

    public static final MapCodec<QuestSettings> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            RewardAutoClaim.CODEC.optionalFieldOf("defaultAutoClaim", RewardAutoClaim.DISABLED)
                    .forGetter(QuestSettings::defaultAutoClaim),
            Codec.BOOL.optionalFieldOf("defaultTeamReward", false).forGetter(QuestSettings::defaultTeamReward),
            Codec.BOOL.optionalFieldOf("suppressAllAutoclaiming", false)
                    .forGetter(QuestSettings::suppressAllAutoclaiming),
            Codec.intRange(0, 72000).optionalFieldOf("detectionDelay", 20).forGetter(QuestSettings::detectionDelay),
            Codec.STRING.optionalFieldOf("bookTitle", "").forGetter(QuestSettings::bookTitle),
            Codec.STRING.optionalFieldOf("bookIcon", "").forGetter(QuestSettings::bookIcon)
    ).apply(instance, QuestSettings::new));

    public static final Codec<QuestSettings> CODEC = MAP_CODEC.codec();

    /**
     * The settings an {@code index.json} at this root declares, or the defaults.
     *
     * <p>Never throws: this runs during a load whose whole promise is that a broken file produces a
     * useful message rather than a crash, and a settings block is not worth more than the questline.
     */
    public static QuestSettings load(Path questRoot) {
        Path indexPath = questRoot.resolve(QuestFiles.INDEX_MANIFEST);
        if (!Files.isRegularFile(indexPath)) {
            return DEFAULTS;
        }
        JsonElement root;
        try {
            root = JsonParser.parseString(Files.readString(indexPath));
        }
        catch (IOException | RuntimeException e) {
            Constants.LOG.warn("Tasked: could not read {} for its settings; using the defaults ({})",
                    indexPath, e.getMessage());
            return DEFAULTS;
        }
        if (!root.isJsonObject()) {
            return DEFAULTS;
        }
        JsonObject settings = root.getAsJsonObject().getAsJsonObject("settings");
        if (settings == null) {
            return DEFAULTS;
        }
        return CODEC.parse(JsonOps.INSTANCE, settings).resultOrPartial(problem ->
                Constants.LOG.warn("Tasked: index.json settings: {}", problem)).orElse(DEFAULTS);
    }
}
