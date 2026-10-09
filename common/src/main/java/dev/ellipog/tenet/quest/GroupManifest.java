package dev.ellipog.tenet.quest;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.List;
import java.util.Optional;

/**
 * One {@code group.json}: a folder's account of itself, and of the chapter folders beside it.
 *
 * <pre>{@code
 * {
 *   "$schema": "../_schema/group.schema.json",
 *   "id": "getting_started",
 *   "title": "Getting Started",
 *   "chapters": ["first_steps", "tools"]
 * }
 * }</pre>
 *
 * <h2>Why this is a sibling of ChapterGroup rather than a use of it</h2>
 *
 * <p>Because the two differ in exactly one field, and that difference is the whole of what version 2
 * changes: {@code chapters} here is a list of <b>names</b>, and on {@link ChapterGroup} it is a list of
 * {@link Chapter} objects. A manifest describes what is on disk; a group is what the loader assembled
 * from those files. Folding them together would mean a {@code ChapterGroup} whose chapters are strings
 * it cannot resolve, which is a type that lies about what it holds.
 *
 * <p>So the fields are duplicated, and the duplication is the point rather than an oversight — this
 * record and {@code ChapterGroup} describe the same object at two moments. What keeps them in step is
 * that {@link #toGroup(List)} is the only place either is turned into the other, plus
 * {@code QuestManifestTest}, which asserts the two field sets are equal. That test exists because the
 * alternative — a comment asking future editors to keep them aligned — is the arrangement this
 * codebase has already recorded as not working.
 *
 * <h2>No version field, deliberately</h2>
 *
 * <p>A version-1 file carries {@code "version": 1} because a migration needs something to key off. A
 * per-kind file does not, and it is the same argument {@link QuestFiles} makes for recognising version
 * 1 by position rather than by the field: <b>the folder layout already says which format this is.</b>
 * A {@code group.json} inside a group folder cannot be anything but version 2, so a version number in
 * it would be a second answer to a question that is already settled — and a second answer is a thing
 * that can disagree, which is how you get a file that claims to be version 3 and is read as 2.
 *
 * <h2>{@code $schema} is ignored, and is the reason the {@code _schema} folder is skipped</h2>
 *
 * <p>Nothing here reads it; it is for an editor. The reference is a depth-counted relative path
 * ({@code ../_schema/group.schema.json}, one level up from a group folder), which is why the schemas
 * live in a folder the walk skips by name — a walker that did not would report every schema file as
 * unlisted content, which is a mod error-walling about a folder it shipped itself.
 *
 * @param id                the group's id. Must equal its folder's name, which {@link QuestFiles}
 *                          checks and reports naming both sides.
 * @param title             what the book writes on the group's row
 * @param description       paragraphs, or a single string. See {@link QuestText#LIST_OR_ONE}.
 * @param icon              the item the book draws on the group's row, or empty for "use the first
 *                          chapter's". See {@link ChapterGroup#icon()}.
 * @param aliases           former ids, so a rename does not orphan progress or break a reference
 * @param collapsedByDefault whether the book shows this group's chapters the first time it sees a tree
 * @param tags              words this group answers to in lookups by tag, {@code "#tag"}
 * @param chapters          the <b>folder names</b> of this group's chapters, in the order they should
 *                          appear. Author-chosen, because nothing else can express intent here.
 */
public record GroupManifest(
        String id,
        QuestText title,
        List<QuestText> description,
        Optional<Icon> icon,
        List<String> aliases,
        boolean collapsedByDefault,
        List<String> tags,
        List<String> chapters
) {

    /** The field names this contributes. Equal to {@link ChapterGroup#FIELDS} — see the class note. */
    public static final java.util.Set<String> FIELDS = java.util.Set.of(
            "id", "title", "description", "icon", "aliases", "collapsedByDefault", "tags", "chapters");

    /**
     * This manifest as a group, with the chapters its names resolved to.
     *
     * <p>The one place the two records meet. A caller passes the chapters in <b>declaration order</b> —
     * the order {@link #chapters} names them — because that order is the author's and is what the book
     * draws; resolving the names itself is {@link QuestFiles}' job, since it is the step that holds the
     * folder the names resolve against.
     */
    public ChapterGroup toGroup(List<Chapter> resolved) {
        return new ChapterGroup(id, title, description, icon, aliases, collapsedByDefault, tags,
                resolved);
    }

    public static final Codec<GroupManifest> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("id").forGetter(GroupManifest::id),
            QuestText.CODEC.fieldOf("title").forGetter(GroupManifest::title),
            QuestText.LIST_OR_ONE.optionalFieldOf("description", List.of())
                    .forGetter(GroupManifest::description),
            Icon.CODEC.optionalFieldOf("icon").forGetter(GroupManifest::icon),
            Codec.STRING.listOf().optionalFieldOf("aliases", List.of()).forGetter(GroupManifest::aliases),
            Codec.BOOL.optionalFieldOf("collapsedByDefault", false)
                    .forGetter(GroupManifest::collapsedByDefault),
            Codec.STRING.listOf().optionalFieldOf("tags", List.of()).forGetter(GroupManifest::tags),
            Codec.STRING.listOf().optionalFieldOf("chapters", List.of()).forGetter(GroupManifest::chapters)
    ).apply(instance, GroupManifest::new));
}
