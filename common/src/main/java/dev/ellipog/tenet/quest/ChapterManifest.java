package dev.ellipog.tenet.quest;

import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.ellipog.armature.api.data.Codecs;

import java.util.List;
import java.util.Optional;

/**
 * One {@code chapter.json}: a folder's account of itself, and of the quest files beside it.
 *
 * <pre>{@code
 * {
 *   "$schema": "../../_schema/chapter.schema.json",
 *   "id": "first_steps",
 *   "title": "First Steps",
 *   "icon": { "item": "minecraft:crafting_table" },
 *   "progressionMode": "linear",
 *   "quests": ["punch_a_tree.json", "make_a_table.json"]
 * }
 * }</pre>
 *
 * <h2>The one field that is load-bearing rather than descriptive</h2>
 *
 * <p>{@code quests} is a list of <b>file names</b> in order, and that order <i>is</i> the progression of
 * a {@code LINEAR} chapter — there are no {@code dependsOn} edges to read, because the list is the
 * road. Two things follow, and both are the reason this record exists rather than the loader reading
 * the folder:
 *
 * <ul>
 *   <li>A name absent from this list is a quest that will never load. {@link QuestFiles} reports that
 *       when it walks the folder, because only it can see both the list and the files.</li>
 *   <li>A name <i>present</i> here that is not on disk is a dangling reference, also reported there.
 *       The two directions are different messages, and neither belongs here.</li>
 * </ul>
 *
 * <p>Which is why {@link #toChapter(List)} takes the quests rather than resolving them: this record
 * says what the folder claims, and the assembled {@link Chapter} is what the loader found. Keeping the
 * two apart is what makes "the manifest and the tree disagree" a thing that can be reported at all.
 *
 * <h2>The chapter defaults live here, and not in each quest</h2>
 *
 * <p>{@code progressionMode}, {@code defaultPrerequisiteMode}, {@code defaultConsumeItems}
 * and {@code theme} are the same fields {@link Chapter} carries, for the same reason: a chapter for the same reason: a chapter of thirty
 * quests in a chain would otherwise repeat {@code "prerequisiteMode": "all_completed"} thirty times,
 * and the one place it differed would be the one place nobody noticed. See {@link Chapter}'s javadoc
 * for the argument each of them makes.
 *
 * <p>No version field, and no {@code $schema} handling — both for the reasons {@link GroupManifest}
 * gives at length.
 *
 * @param id                      the chapter's id. Must equal its folder's name.
 * @param title                   what the book writes on the chapter's row
 * @param subtitle                an optional second line
 * @param description             paragraphs, or a single string
 * @param icon                    what the chapter is drawn with, defaulting to paper
 * @param aliases                 former ids
 * @param defaultPrerequisiteMode what every quest in this chapter uses unless it says otherwise
 * @param progressionMode         {@code linear} makes the {@code quests} order the progression
 * @param defaultConsumeItems     whether item tasks here take the items unless the task says otherwise
 * @param theme                   a palette this chapter asks to be drawn in, or none. A <b>client</b>
 *                                concept: the catalogue lives on the client, so this is a plain string
 *                                here and is reported by the client that could not honour it.
 * @param themePatch              token-level overrides for {@code theme}, carried raw for the client to
 *                                parse and compose — see {@link Chapter#themePatch()}
 * @param quests                  the <b>file names</b> of this chapter's quests, in order
 */
public record ChapterManifest(
        String id,
        QuestText title,
        Optional<QuestText> subtitle,
        List<QuestText> description,
        ItemRef icon,
        List<String> aliases,
        PrerequisiteMode defaultPrerequisiteMode,
        ProgressionMode progressionMode,
        boolean defaultConsumeItems,
        /** The chapter's default dependency-line style; see {@link Chapter#dependencyStyle()}. */
        DependencyStyle dependencyStyle,
        Optional<String> theme,
        Optional<JsonObject> themePatch,
        /** The chapter's default auto-claim mode; see {@link Chapter#autoClaim()}. */
        dev.ellipog.tenet.quest.reward.RewardAutoClaim autoClaim,
        /** The chapter's own gate, completion and hiding; see {@link ChapterRules}. */
        ChapterRules rules,
        List<String> quests,
        /**
         * Everything drawn on the canvas that is not a quest.
         *
         * <p>Carried as the canvas itself rather than as JSON, which is the one place this manifest
         * is less "declared names" than the rest of it: a quest is a file this manifest names, and an
         * element or a link has no file to name — each lives in this chapter's own document.
         * See {@link CanvasElement} and {@link QuestLink}.
         */
        ChapterCanvas canvas
) {

    /** The field names this contributes. Equal to {@link Chapter#FIELDS} — see {@link GroupManifest}. */
    public static final java.util.Set<String> FIELDS = java.util.Set.of(
            "id", "title", "subtitle", "description", "icon", "aliases", "defaultPrerequisiteMode",
            "progressionMode", "defaultConsumeItems", "defaultFlexibleProgress", "dependencyStyle",
            "theme", "themePatch", "autoClaim",
            "dependsOn", "prerequisiteMode", "minRequired", "completesWhen",
            "hideUntilDependenciesComplete", "defaultHideUntilDependenciesComplete",
            "defaultHideUntilDependenciesVisible",
            "quests", "elements", "links");

    /**
     * This manifest as a chapter, with the quests its names resolved to.
     *
     * <p>The one place the two records meet. A caller passes the quests in <b>declaration order</b>:
     * the order {@link #quests} names them, which for a {@code LINEAR} chapter is the progression
     * itself. Resolving those names against the folder is {@link QuestFiles}' job.
     */
    public Chapter toChapter(List<Quest> resolved) {
        return new Chapter(id, title, subtitle, description, icon, aliases, defaultPrerequisiteMode,
                progressionMode, defaultConsumeItems, dependencyStyle, theme, themePatch, autoClaim, rules,
                resolved, canvas);
    }

    /**
     * This manifest's elements, for the callers that predate the canvas grouping.
     *
     * <p>Like {@link Chapter#elements()}: the grouping is a codec's answer to a codec's limit, not
     * a concept an author meets.
     */
    public List<CanvasElement> elements() {
        return canvas.elements();
    }

    /** This manifest's links, in declaration order. See {@link Chapter#links()}. */
    public List<QuestLink> links() {
        return canvas.links();
    }

    public static final Codec<ChapterManifest> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("id").forGetter(ChapterManifest::id),
            QuestText.CODEC.fieldOf("title").forGetter(ChapterManifest::title),
            QuestText.CODEC.optionalFieldOf("subtitle").forGetter(ChapterManifest::subtitle),
            QuestText.LIST_OR_ONE.optionalFieldOf("description", List.of())
                    .forGetter(ChapterManifest::description),
            ItemRef.CODEC.optionalFieldOf("icon", ItemRef.DEFAULT_ICON).forGetter(ChapterManifest::icon),
            Codec.STRING.listOf().optionalFieldOf("aliases", List.of()).forGetter(ChapterManifest::aliases),
            PrerequisiteMode.CODEC.optionalFieldOf("defaultPrerequisiteMode", PrerequisiteMode.ALL_COMPLETED)
                    .forGetter(ChapterManifest::defaultPrerequisiteMode),
            // FLEXIBLE by default, matching Chapter's own default and for the same reason: a chapter
            // where declaring a dependency silently did nothing, because the chapter was linear, would
            // be a confusing thing to debug.
            ProgressionMode.CODEC.optionalFieldOf("progressionMode", ProgressionMode.FLEXIBLE)
                    .forGetter(ChapterManifest::progressionMode),
            Codec.BOOL.optionalFieldOf("defaultConsumeItems", false)
                    .forGetter(ChapterManifest::defaultConsumeItems),
            DependencyStyle.CODEC.optionalFieldOf("dependencyStyle", DependencyStyle.UNSET)
                    .forGetter(ChapterManifest::dependencyStyle),
            Codec.STRING.optionalFieldOf("theme").forGetter(ChapterManifest::theme),
            Codecs.jsonObject().optionalFieldOf("themePatch").forGetter(ChapterManifest::themePatch),
            dev.ellipog.tenet.quest.reward.RewardAutoClaim.CODEC
                    .optionalFieldOf("autoClaim", dev.ellipog.tenet.quest.reward.RewardAutoClaim.DEFAULT)
                    .forGetter(ChapterManifest::autoClaim),
            // The same MapCodec the assembled chapter uses, so the manifest and the chapter it becomes
            // read the same five fields at the same paths -- which is what keeps "the manifest says one
            // thing and the tree another" a thing that cannot happen here.
            ChapterRules.MAP_CODEC.forGetter(ChapterManifest::rules),
            Codec.STRING.listOf().optionalFieldOf("quests", List.of()).forGetter(ChapterManifest::quests),
            // The canvas itself, read by the same codec the chapter uses, so a manifest and the
            // chapter it becomes cannot disagree about what an element or a link is.
            ChapterCanvas.MAP_CODEC.forGetter(ChapterManifest::canvas)
    ).apply(instance, ChapterManifest::new));
}
