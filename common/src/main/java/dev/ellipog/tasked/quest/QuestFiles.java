package dev.ellipog.tasked.quest;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.ellipog.armature.api.data.Checks;
import dev.ellipog.armature.api.data.DataProblem;
import dev.ellipog.armature.api.data.JsonDocument;
import dev.ellipog.armature.api.data.JsonLocation;
import dev.ellipog.armature.api.data.JsonParseException;
import dev.ellipog.armature.api.data.Problems;
import dev.ellipog.armature.client.ui.kit.DeclaredPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * What is in {@code config/tasked/quests/}, discovered rather than interpreted.
 *
 * <h2>Why this is a step of its own</h2>
 *
 * <p>Because "which files are quests" is a different question from "is this quest well-formed", and
 * conflating them is how a loader ends up learning the folder rules. Before this, {@link QuestLoader}
 * walked the directory for {@code *.json}, parsed each one and handed the lot to the validator — so
 * the folder layout was expressed as a glob in one place and as a set of JSON paths in another, and
 * neither knew about the other.
 *
 * <p>The walk happens once, here, and comes out as an <b>ordered list of declarations</b>: what each
 * file is, what it says it is, and what it names underneath it. Everything downstream — the
 * validator's per-kind field sets, the loader's decode, the geometry dump's tree mode — reads that
 * list instead of reading the directory. There is one description of the folder rules, and this is it.
 *
 * <h2>The shape it reads</h2>
 *
 * <pre>
 *   config/tasked/quests/
 *     _schema/                    skipped, everywhere, by name
 *     getting_started/            a group folder
 *       group.json                its manifest: id + title + chapters[] in order
 *       first_steps/              a chapter folder
 *         chapter.json            its manifest: id + title + quests[] in order
 *         punch_a_tree.json       one whole quest per file
 *     stuck_in_the_past.json      a version-1 flat file, still read forever
 * </pre>
 *
 * <p>A folder's <b>name is its id</b>, and that is a check rather than a convention. A folder called
 * {@code getting_started} whose manifest says {@code "id": "first_steps"} is drift in the one place a
 * reader would never look — the tree and the manifest agree about everything except which thing they
 * are describing — so it is an error naming both sides, and the folder name wins, because the folder
 * name is what every path in the tree is built from.
 *
 * <h2>Order comes from three different places, and each is the right one</h2>
 *
 * <ol>
 *   <li><b>Groups: folder name.</b> Nothing declares the order of the group folders, because there is
 *       no file above them to declare it. Folder-name order is the only order available and the only
 *       one a person can predict. The consequence worth stating, because it surprises the first time:
 *       <b>renaming a group folder can reorder the book</b>, and the default chapter with it.</li>
 *   <li><b>Chapters within a group: the manifest's {@code chapters} array.</b> Author-chosen, and the
 *       only order that can express intent.</li>
 *   <li><b>Quests within a chapter: the manifest's {@code quests} array.</b> Load-bearing rather than
 *       cosmetic: a {@code LINEAR} chapter's progression <i>is</i> this order, so the list a manifest
 *       declares is the order the tree is walked in and the order the decoded chapter comes out in.</li>
 * </ol>
 *
 * <h2>Version 1 is read forever, and it is not a special case here</h2>
 *
 * <p>A flat {@code .json} file at the quest root is a version-1 file: one document holding the whole
 * {@code chapterGroups[]} tree. This class does not parse it beyond recognising it by <i>position</i>
 * — a file at the root rather than a folder — and hands the parsed document over for the loader to
 * read with the version-1 code path. The two formats meet at exactly one point: a root entry that
 * happens to be a file rather than a folder.
 *
 * <p><b>Recognising by position rather than by the {@code version} field</b> is deliberate. A file
 * written before versions existed has no {@code version} at all, and one written by hand may have it
 * wrong; the thing that actually distinguishes the two formats is whether the root entry is a folder
 * tree or a single document with {@code chapterGroups} in it. Deciding on a number the author has to
 * remember to update would make an old file unreadable for a reason with no symptom.
 *
 * <h2>What it does not do</h2>
 *
 * <p>No validation of field names, no decoding, no cross-file checks. A declaration here has been
 * <i>found</i> and its own manifest's references have been <i>resolved</i> — nothing more. Whether the
 * thing it found is well-formed is {@link QuestValidator}'s question, and answering it here would mean
 * one mistake producing two messages, which is the fault the whole two-phase arrangement exists to
 * avoid.
 *
 * <p><b>Reads only.</b> Nothing here creates, writes, renames or deletes — the same rule
 * {@link QuestLoader} states at length, and for the same reason: this directory belongs to whoever is
 * playing.
 *
 * <p><b>Minecraft-free.</b> Folders, JSON and problems; no game type appears. That matters more here
 * than anywhere else in the loader, because every rule in this file produces an <i>error message</i>,
 * and an error message is the part of a loader nobody can check by looking at it.
 */
public final class QuestFiles {

    /** The manifest a group folder must contain. Its name is fixed, not declared. */
    public static final String GROUP_MANIFEST = "group.json";

    /** The manifest a chapter folder must contain. Likewise fixed. */
    public static final String CHAPTER_MANIFEST = "chapter.json";

    /**
     * Where the editor schemas live, relative to the quest root.
     *
     * <p>Underscore-prefixed so the walk skips it, which is the whole reason the prefix rule exists:
     * the mod ships this folder beside its own content, and a walker that did not skip it would report
     * every file inside as unlisted content — a mod error-walling on a folder it wrote itself.
     */
    public static final String SCHEMA_DIRECTORY = "_schema";

    /**
     * The optional manifest at the quest root, which declares the order of the top level.
     *
     * <h2>What it is for, and what its absence means</h2>
     *
     * <p>Groups used to be ordered by folder name, because there was no file above them to declare an
     * order. That is fine until somebody wants to move one — a reorder by rename is not a reorder, it
     * is an id change — so this file is the place above them. It lists every top-level entry of the
     * book in order: a group, a chapter that belongs to no group, or a version-1 file.
     *
     * <p><b>Absence is not a fault.</b> A tree with no {@code index.json} is read exactly as it always
     * was: every root folder is a group, and groups are ordered by folder name. That is what makes this
     * addition backwards compatible by construction rather than by a migration, and it is why the
     * loader only starts requiring an entry for everything once the file exists.
     */
    public static final String INDEX_MANIFEST = "index.json";

    /**
     * The suffix a file or folder carries once it has been deleted through the editor.
     *
     * <p>The delete in this mod is recoverable: a quest file becomes {@code <id>.json.deleted} rather
     * than being erased. Explorer deletes are the same idea at folder scale — {@code <id>.deleted},
     * with the contents inside — so the walk has to skip that suffix at every level, exactly as it
     * skips the underscore prefix. The two rules are different in kind: {@code _} means "not content,
     * deliberately", and {@code .deleted} means "content that was removed, kept in case". Both mean
     * the walk must not read it.
     */
    public static final String DELETED_SUFFIX = ".deleted";

    /** Whether a single name is a recoverable delete, and so skipped wherever it appears. */
    public static boolean isDeletedName(String name) {
        return name != null && name.endsWith(DELETED_SUFFIX);
    }

    /**
     * The root folder reward tables live in, beside the book rather than inside it.
     *
     * <p>A table is not a sidebar row, not a chapter and not a quest: it is a named roll of rewards a
     * {@code tasked:random}/{@code loot}/{@code all_table}/{@code choice} reward points at. Putting
     * them under {@code index.json} would have meant a fourth entry kind and a table pretending to be
     * book order; the folder is reserved by name instead, the way {@code _schema} is reserved by
     * prefix. Unlike that one, this name carries content, so it is a word rather than an underscore:
     * an author should be able to find it.
     */
    public static final String REWARD_TABLES_DIRECTORY = "reward_tables";

    /** Whether a root entry is Tasked's own storage rather than book content. */
    public static boolean isReservedName(String name) {
        return REWARD_TABLES_DIRECTORY.equals(name);
    }

    /**
     * The reward-table files at the root, name-sorted. Empty when there is no such folder.
     *
     * <p>Read outside the discovery walk on purpose: a table is not a {@code Declaration} and must
     * not travel through the book's assembly, where every new kind drags through the index, the
     * geometry and the sync. The walk only needs to know to leave the folder alone.
     */
    public static List<Path> rewardTableFiles(Path questRoot) {
        Path folder = questRoot.resolve(REWARD_TABLES_DIRECTORY);
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        List<Path> entries = listSorted(folder);
        if (entries == null) {
            return List.of();
        }
        List<Path> files = new ArrayList<>();
        for (Path entry : entries) {
            String name = entry.getFileName().toString();
            // The `_` rule applies here like everywhere else. It did not, so `reward_tables/_draft.json`
            // was read as a table whose id was `_draft` -- synced to clients and nameable by a reward --
            // while the manual's "any file or folder whose name begins with `_` is skipped" said it was
            // a note. See `DeclaredPaths.isIgnoredName`, which the other four walks already call.
            if (Files.isRegularFile(entry) && isQuestFile(name) && !isDeletedName(name)
                    && !DeclaredPaths.isIgnoredName(name)) {
                files.add(entry);
            }
        }
        return List.copyOf(files);
    }

    private QuestFiles() {
    }

    // ------------------------------------------------------------------
    // What a discovery produces
    // ------------------------------------------------------------------

    /** Which of the three declarations a file is. */
    public enum Kind {

        /** A group folder's {@code group.json}. */
        GROUP("chapter group"),

        /** A chapter folder's {@code chapter.json}. */
        CHAPTER("chapter"),

        /** One whole quest, in a file of its own. */
        QUEST("quest"),

        /** A version-1 flat file: a whole tree in one document, read forever. */
        FLAT_V1("version-1 file");

        private final String word;

        Kind(String word) {
            this.word = word;
        }

        /** How to say it in a message. */
        public String word() {
            return word;
        }
    }

    /**
     * One file that was found, and what its own manifest says about it.
     *
     * <p>The document is kept rather than discarded, for the reason {@link LoadedQuestFile} gives at
     * length: a problem found later has to be able to name a line and a column, and re-reading the file
     * to find out would mean two parses of one file that could disagree about a line number.
     *
     * @param kind             which of the three this is
     * @param path             where it is on disk
     * @param display          its path relative to the quest root, {@code /}-separated, for messages
     * @param parentDisplay    the display of the declaration this one sits under, or null at the root
     * @param document         the parsed file
     * @param declaredId       the {@code id} the file declares, or null when it declares none
     * @param folderName       the name of the folder containing it — for the folder-name check
     * @param declaredChildren the names this file lists, in order. Empty for a quest or a v1 file.
     */
    public record Declaration(Kind kind,
                              Path path,
                              String display,
                              String parentDisplay,
                              JsonDocument document,
                              String declaredId,
                              String folderName,
                              List<String> declaredChildren) {

        /**
         * The id the tree is built from: the folder's name, which the manifest has to agree with.
         *
         * <p>This exists so that "the folder name wins" is something the code does rather than
         * something the class comment says. {@link #declaredId} is what the file <i>wrote</i> — kept
         * because a mismatch message has to name both sides, and the side that is wrong may be either
         * one. This is the side that is used: every path in the tree, every error message's file name
         * and every lookup downstream is built from the folder, so it is the one that has to be right,
         * and a mistake in it is a mistake in a directory listing rather than in a document.
         *
         * <p>For a quest file or a version-1 flat file there is no folder of its own and the two are
         * the same thing, which is why this falls back rather than refusing.
         */
        public String id() {
            return folderName != null ? folderName : declaredId;
        }

        /** Where this is, for a message: {@code getting_started/first_steps/punch_a_tree.json:14:9}. */
        public String location(String jsonPath) {
            return display + ":" + document.nearestLocation(jsonPath);
        }
    }

    /**
     * Everything found, in the order the tree is built, plus what was wrong with it.
     *
     * <p>{@code declarations} is <b>depth-first</b>: a group, then its chapters, then each chapter's
     * quests, then the next group. That is deliberate rather than incidental — it is the order a tree
     * is written out, so a caller that walks the list in order and keeps a stack by {@code
     * parentDisplay} reconstructs the tree without a second pass, and a caller that only wants "every
     * quest file" reads the same list filtered.
     *
     * <p>A file that could not be parsed, or that failed the folder-name check, is <b>not</b> in the
     * list: it is a problem, and a declaration that cannot be interpreted is worse than an absent one,
     * because everything downstream would try to interpret it.
     */
    public record Discovery(List<Declaration> declarations, Problems problems, int filesExamined) {

        /** Whether discovery found anything fatal. A warning never blocks a load. */
        public boolean ok() {
            return !problems.hasErrors();
        }

        /**
         * How many files were opened, whether or not they were understood.
         *
         * <h2>Why this is separate from {@code declarations.size()}</h2>
         *
         * <p>Because a file that failed to parse is <b>not</b> in {@code declarations} — deliberately,
         * since nothing downstream can interpret it — so counting the declarations answers "how many
         * did this understand", which is a different and less useful question than "how many did it
         * look at".
         *
         * <p>Concretely: a directory of three flat files with one typo in it has three files present,
         * two understood, and one error naming the third. Counted from the declarations, the report
         * would say "loaded 2 of 2 files, 1 had errors" — a sentence that cannot be true, and which
         * sends a reader looking for a third file that the count insists is not there.
         *
         * <p>Files that are <i>absent</i> are not counted, because a missing {@code group.json} is not a
         * file that was examined. That matches the rule this replaced, which counted files that matched
         * a pattern and therefore only ever counted ones that existed.
         */
        public int filesExamined() {
            return filesExamined;
        }

        /** Every declaration of one kind, in tree order. */
        public List<Declaration> of(Kind kind) {
            return declarations.stream().filter(d -> d.kind() == kind).toList();
        }

        /** The display names of every quest file, in tree order — what the geometry dump needs. */
        public List<String> questDisplays() {
            return declarations.stream()
                    .filter(d -> d.kind() == Kind.QUEST)
                    .map(Declaration::display)
                    .toList();
        }

        /** How many files were found, of every kind. */
        public int size() {
            return declarations.size();
        }
    }

    // ------------------------------------------------------------------
    // The walk
    // ------------------------------------------------------------------

    /**
     * Reads the folder tree under {@code questRoot}.
     *
     * <p>An absent or empty root is not an error and not a warning here: {@link QuestLoader} reports
     * that, because it is the caller that knows what an absent directory means for a fresh install.
     * This method's contract is "say what is there", and "nothing is there" is a valid answer to it.
     */
    public static Discovery discover(Path questRoot) {
        List<Declaration> declarations = new ArrayList<>();
        Problems problems = new Problems();

        // One element, threaded through the walk, because the walk is static and the count is not a
        // property of any one step. An int[] rather than a field, because QuestFiles is stateless on
        // purpose: a load is a pure function of what is on disk, and a static counter would make two
        // concurrent loads disagree about a number that describes neither of them.
        int[] examined = {0};

        List<Path> entries = listSorted(questRoot);
        if (entries == null) {
            return new Discovery(List.of(), problems, 0);
        }

        Path indexPath = questRoot.resolve(INDEX_MANIFEST);
        if (Files.isRegularFile(indexPath)) {
            discoverIndexed(questRoot, indexPath, entries, declarations, problems, examined);
        }
        else {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (DeclaredPaths.isIgnoredName(name) || isDeletedName(name) || isReservedName(name)) {
                    // `_schema/` and every other underscore-prefixed name at the root, anything a
                    // recoverable delete left behind, and the reward tables' folder -- which is
                    // content, just not the book's. See the class note.
                    continue;
                }
                if (Files.isDirectory(entry) && !Files.isSymbolicLink(entry)) {
                    discoverGroup(questRoot, entry, declarations, problems, examined);
                }
                else if (isQuestFile(name)) {
                    discoverFlatFile(questRoot, entry, declarations, problems, examined);
                }
            }
        }

        return new Discovery(List.copyOf(declarations), problems, examined[0]);
    }

    /**
     * The root when an {@code index.json} declares it.
     *
     * <h2>What changes, and what does not</h2>
     *
     * <p>Everything the index lists is walked in the order it lists it, and a root chapter is walked
     * as a chapter rather than rejected for having no group folder above it — that is the whole point
     * of the manifest. A group entry resolves to a group folder exactly as the unindexed walk would
     * have found it, so the rules <i>inside</i> a group do not change at all.
     *
     * <p>Everything at the root that the index does <b>not</b> name is an error. That is the same
     * choice {@code chapters} and {@code quests} already make one level down, and for the same reason:
     * content sitting in the tree that nothing will read is invisible, and only the author can say
     * whether it is a mistake or a note to be prefixed. The one asymmetry is that this rule exists
     * only while the manifest does — without it there is nothing to be listed in, and the old
     * folder-name walk is the honest reading.
     */
    private static void discoverIndexed(Path root, Path indexPath, List<Path> entries,
                                        List<Declaration> out, Problems problems, int[] examined) {
        String indexDisplay = display(root, indexPath);
        examined[0]++;
        Optional<JsonDocument> parsed = parse(indexPath, indexDisplay, problems);
        if (parsed.isEmpty()) {
            return;
        }
        JsonDocument document = parsed.get();

        JsonElement list = document.get("$.entries").orElse(null);
        if (list == null || !list.isJsonArray()) {
            problems.error(document, "$.entries", list == null
                    ? "no \"entries\" - " + INDEX_MANIFEST + " has to list the top level of the book, in"
                            + " order. An empty list is a valid answer and an absent one is not, because"
                            + " the two say different things about the tree."
                    : "expected a list of entries, found " + Checks.kindOf(list) + ". Each entry names a"
                            + " \"group\", a \"chapter\" or a version-1 \"file\".");
            return;
        }

        // What this manifest accounted for, so the sweep afterwards can say what it did not. The
        // manifest itself is in the set, or the sweep would report the file it just read.
        Set<String> listed = new LinkedHashSet<>();
        listed.add(INDEX_MANIFEST);

        int index = 0;
        for (JsonElement element : list.getAsJsonArray()) {
            String entryPath = "$.entries[" + (index++) + "]";
            if (!element.isJsonObject()) {
                problems.error(document, entryPath, "expected an object naming a \"group\", a \"chapter\""
                        + " or a version-1 \"file\", found " + Checks.kindOf(element));
                continue;
            }
            JsonObject object = element.getAsJsonObject();
            String key = null;
            int named = 0;
            for (String candidate : List.of("group", "chapter", "file")) {
                if (object.has(candidate)) {
                    key = candidate;
                    named++;
                }
            }
            if (named != 1) {
                problems.error(document, entryPath, named == 0
                        ? "this entry names nothing. Write exactly one of \"group\", \"chapter\" or"
                                + " \"file\"."
                        : "this entry names more than one kind. An entry is one group, one chapter or one"
                                + " version-1 file, and it cannot be two of them.");
                continue;
            }
            JsonElement value = object.get(key);
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                problems.error(document, entryPath + "." + key, "expected the name as a string, found "
                        + Checks.kindOf(value));
                continue;
            }
            String name = value.getAsString();
            if (!listed.add(name)) {
                problems.error(document, entryPath + "." + key, "\"" + name + "\" is listed more than"
                        + " once. An entry is the whole of that thing's place in the book, so a second"
                        + " mention has no meaning to give it.");
                continue;
            }

            DeclaredPaths.Kind want = key.equals("file") ? DeclaredPaths.Kind.FILE
                    : DeclaredPaths.Kind.DIRECTORY;
            DeclaredPaths.Resolved resolved = DeclaredPaths.resolveSibling(root, name, want);
            if (!resolved.ok()) {
                problems.add(document, entryPath, DataProblem.Severity.ERROR, resolved.problem());
                continue;
            }

            switch (key) {
                case "group" -> discoverGroup(root, resolved.path(), out, problems, examined);
                // A null parent: at the root there is no manifest above this one, and that null is also
                // what tells the loader this chapter has no group. See `QuestLoader.assemble`.
                case "chapter" -> discoverChapter(root, resolved.path(), null, out, problems, examined);
                case "file" -> discoverFlatFile(root, resolved.path(), out, problems, examined);
                default -> throw new IllegalStateException("unreachable entry kind " + key);
            }
        }

        for (Path entry : entries) {
            String name = entry.getFileName().toString();
            if (DeclaredPaths.isIgnoredName(name) || isDeletedName(name) || isReservedName(name)
                    || listed.contains(name)) {
                continue;
            }
            problems.add(display(root, entry), new JsonLocation(1, 1, "$"), DataProblem.Severity.ERROR,
                    "this is at the root of the quest tree and " + INDEX_MANIFEST + " does not mention it"
                            + " - so it will never load. Add an entry for \"" + name + "\" in the order it"
                            + " should sit, or prefix the name with \"_\" to leave it out deliberately.");
        }
    }

    /**
     * A version-1 flat file at the root.
     *
     * <p>Recognised by not being in a folder. It has no children to resolve, so this is the whole of
     * its discovery — parse it and pass it on. The loader reads it through the version-1 path, which is
     * the only place the two formats differ.
     */
    private static void discoverFlatFile(Path root, Path file, List<Declaration> out, Problems problems,
                                         int[] examined) {
        String display = display(root, file);
        examined[0]++;
        parse(file, display, problems).ifPresent(document -> out.add(new Declaration(
                Kind.FLAT_V1, file, display, null, document, stringAt(document, "$.id"), null, List.of())));
    }

    /**
     * A group folder: its manifest, its chapters, and nothing else.
     *
     * <p>Four ways this can go wrong and all of them are reported rather than guessed at:
     *
     * <ul>
     *   <li>No {@code group.json} — the folder is not a group. Reported, because a folder of content
     *       sitting where content is read is almost certainly meant to be one.</li>
     *   <li>The manifest does not parse — reported against the manifest's own line, and the folder is
     *       skipped, because a manifest that cannot be read does not say which chapters belong to
     *       it.</li>
     *   <li>Its {@code id} disagrees with the folder name — reported naming both, and the folder is
     *       still read, with the folder name winning. That is the honest choice: the folder name is
     *       what every path in the tree is built from, so it is the one that has to be right, and
     *       treating the folder as absent would hide the rest of an otherwise-fine group.</li>
     *   <li>A file or directory inside it that its {@code chapters} list does not account for —
     *       reported, and the fix in both cases is the same prefix.</li>
     * </ul>
     */
    private static void discoverGroup(Path root, Path folder, List<Declaration> out, Problems problems,
                                      int[] examined) {
        String folderName = folder.getFileName().toString();
        Path manifestPath = folder.resolve(GROUP_MANIFEST);
        String manifestDisplay = display(root, manifestPath);

        if (!Files.isRegularFile(manifestPath)) {
            problems.add(manifestDisplay, new JsonLocation(1, 1, "$"), DataProblem.Severity.ERROR,
                    "this folder is not a chapter group: there is no " + GROUP_MANIFEST + " in it."
                            + " A group folder holds " + GROUP_MANIFEST + " and one folder per chapter;"
                            + " if this folder is not meant to be a group, prefix its name with \"_\" so"
                            + " the loader skips it.");
            return;
        }

        examined[0]++;
        Optional<JsonDocument> parsed = parse(manifestPath, manifestDisplay, problems);
        if (parsed.isEmpty()) {
            return;
        }
        JsonDocument document = parsed.get();

        String declaredId = stringAt(document, "$.id");
        if (declaredId == null) {
            problems.error(document, "$", "missing required field id - a group folder's " + GROUP_MANIFEST
                    + " has to name the group, and the folder it is in is called \"" + folderName + "\"");
            return;
        }
        if (!declaredId.equals(folderName)) {
            // A warning, not an error, and that is the whole of finding 5: an error against this file
            // makes QuestLoader refuse the declaration, which nulls the current group -- so the group,
            // its chapters and every quest under them disappeared, while this javadoc, the manual and
            // the discovery test all promised the folder still wins. The message is unchanged: both
            // sides are still named, and the author still has one thing to fix.
            problems.warn(document, "$.id",
                    "this file declares id \"" + declaredId + "\" and the folder it is in is called \""
                            + folderName + "\". A group folder's name is its id - the two have to agree,"
                            + " or the tree and the manifest describing it have drifted apart."
                            + " Rename the folder to \"" + declaredId + "\", or change the id.");
        }

        List<String> declared = childNames(document, "$.chapters", Kind.CHAPTER, problems);
        out.add(new Declaration(Kind.GROUP, manifestPath, manifestDisplay, null, document,
                declaredId, folderName, declared));

        List<Path> entries = listSorted(folder);
        if (entries == null) {
            return;
        }

        for (Path entry : entries) {
            String name = entry.getFileName().toString();
            if (DeclaredPaths.isIgnoredName(name) || name.equals(GROUP_MANIFEST) || isDeletedName(name)) {
                continue;
            }
            if (!Files.isDirectory(entry)) {
                // A file beside group.json. Not a chapter and not a manifest, so nothing will ever read
                // it. Reported rather than ignored, and the fix is a prefix: a note kept beside content
                // is a `_`-prefixed name, which is what the convention is for.
                problems.add(display(root, entry), new JsonLocation(1, 1, "$"), DataProblem.Severity.ERROR,
                        "this file is in a group folder, so the loader expects it to be a chapter folder."
                                + " A group folder holds " + GROUP_MANIFEST + " and one folder per chapter."
                                + " Prefix the name with \"_\" if it is a note, or move it out.");
                continue;
            }
            if (!declared.contains(name)) {
                problems.add(document, "$.chapters", DataProblem.Severity.ERROR,
                        "the folder \"" + name + "\" is here, and this group's \"chapters\" list does not"
                                + " mention it - so its quests will never load. Add \"" + name
                                + "\" to \"chapters\", or prefix the folder with \"_\" to leave it out"
                                + " deliberately.");
            }
        }

        // Declared order, because that is the author's order and the only thing that can express it.
        // A name that did not resolve was reported and is skipped here; the rest of the chapter still
        // loads, which is the difference between one bad reference and a dead group.
        for (String name : declared) {
            DeclaredPaths.Resolved resolved = DeclaredPaths.resolveSibling(
                    folder, name, DeclaredPaths.Kind.DIRECTORY);
            if (!resolved.ok()) {
                problems.add(document, "$.chapters", DataProblem.Severity.ERROR, resolved.problem());
                continue;
            }
            discoverChapter(root, resolved.path(), manifestDisplay, out, problems, examined);
        }
    }

    /**
     * A chapter folder: its manifest, and one file per quest.
     *
     * <p>The same four shapes as {@link #discoverGroup}, with one addition that only exists here — a
     * chapter's {@code quests} list order is load-bearing, because it <i>is</i> the progression of a
     * {@code LINEAR} chapter. So the declared order is the order this walks, and the order the decoded
     * chapter's quest list comes out in.
     */
    private static void discoverChapter(Path root, Path folder, String parentDisplay,
                                        List<Declaration> out, Problems problems, int[] examined) {
        String folderName = folder.getFileName().toString();
        Path manifestPath = folder.resolve(CHAPTER_MANIFEST);
        String manifestDisplay = display(root, manifestPath);

        if (!Files.isRegularFile(manifestPath)) {
            problems.add(manifestDisplay, new JsonLocation(1, 1, "$"), DataProblem.Severity.ERROR,
                    "this folder is not a chapter: there is no " + CHAPTER_MANIFEST + " in it."
                            + " A chapter folder holds " + CHAPTER_MANIFEST + " and one file per quest.");
            return;
        }

        examined[0]++;
        Optional<JsonDocument> parsed = parse(manifestPath, manifestDisplay, problems);
        if (parsed.isEmpty()) {
            return;
        }
        JsonDocument document = parsed.get();

        String declaredId = stringAt(document, "$.id");
        if (declaredId == null) {
            problems.error(document, "$", "missing required field id - a chapter folder's "
                    + CHAPTER_MANIFEST + " has to name the chapter, and the folder it is in is called \""
                    + folderName + "\"");
            return;
        }
        if (!declaredId.equals(folderName)) {
            problems.error(document, "$.id",
                    "this file declares id \"" + declaredId + "\" and the folder it is in is called \""
                            + folderName + "\". A chapter folder's name is its id - the two have to agree."
                            + " Rename the folder to \"" + declaredId + "\", or change the id.");
        }

        List<String> declared = childNames(document, "$.quests", Kind.QUEST, problems);
        out.add(new Declaration(Kind.CHAPTER, manifestPath, manifestDisplay, parentDisplay, document,
                declaredId, folderName, declared));

        List<Path> entries = listSorted(folder);
        if (entries == null) {
            return;
        }

        for (Path entry : entries) {
            String name = entry.getFileName().toString();
            if (DeclaredPaths.isIgnoredName(name) || name.equals(CHAPTER_MANIFEST) || isDeletedName(name)) {
                continue;
            }
            if (Files.isDirectory(entry)) {
                problems.add(display(root, entry), new JsonLocation(1, 1, "$"), DataProblem.Severity.ERROR,
                        "this is a folder inside a chapter folder. A chapter holds " + CHAPTER_MANIFEST
                                + " and one file per quest, and nothing else - nesting is what the group"
                                + " level is for. Prefix the name with \"_\" to leave it out"
                                + " deliberately.");
                continue;
            }
            if (!isQuestFile(name)) {
                // A non-JSON file beside a chapter. Harmless: it is not a quest and nothing will read
                // it, and unlike the group level there is no other kind of thing it could have been
                // meant to be.
                continue;
            }
            if (!declared.contains(name)) {
                problems.add(document, "$.quests", DataProblem.Severity.ERROR,
                        "the quest file \"" + name + "\" is here, and this chapter's \"quests\" list does"
                                + " not mention it - so it will never load. Add \"" + name
                                + "\" to \"quests\" in the order it should sit, or prefix the file with"
                                + " \"_\" to leave it out deliberately.");
            }
        }

        for (String name : declared) {
            DeclaredPaths.Resolved resolved = DeclaredPaths.resolveSibling(
                    folder, name, DeclaredPaths.Kind.FILE);
            if (!resolved.ok()) {
                problems.add(document, "$.quests", DataProblem.Severity.ERROR, resolved.problem());
                continue;
            }
            String questDisplay = display(root, resolved.path());
            examined[0]++;
            parse(resolved.path(), questDisplay, problems).ifPresent(questDocument -> out.add(
                    new Declaration(Kind.QUEST, resolved.path(), questDisplay, manifestDisplay,
                            questDocument, stringAt(questDocument, "$.id"), null, List.of())));
        }
    }

    // ------------------------------------------------------------------
    // Reading a manifest's child list
    // ------------------------------------------------------------------

    /**
     * The names a manifest lists under {@code path}, or an empty list with a reason.
     *
     * <p>Three failures are possible and each gets its own message: the key is absent (a warning — an
     * empty group is a legitimate placeholder, which is the same call {@link QuestValidator} makes); it
     * is present and is not an array; or it holds something that is not a string. The third is worth
     * separating because it is the classic hand-editing mistake — copying a whole chapter object out of
     * the old format into the new list — and reporting it as "expected an array of names" would name
     * the wrong fault.
     */
    private static List<String> childNames(JsonDocument document, String path, Kind childKind,
                                           Problems problems) {
        JsonElement element = document.get(path).orElse(null);
        if (element == null) {
            problems.warn(document, path, "no \"" + Checks.nameOf(path) + "\" - this "
                    + childKind.word() + " is empty");
            return List.of();
        }
        if (!element.isJsonArray()) {
            problems.error(document, path, "expected a list of names, found " + Checks.kindOf(element)
                    + ". A manifest lists the names of its own children, in the order they should"
                    + " appear, and each name is a single path segment resolved against this file's"
                    + " folder - not a nested object and not a path.");
            return List.of();
        }

        List<String> names = new ArrayList<>();
        int index = 0;
        for (JsonElement child : element.getAsJsonArray()) {
            String childPath = path + "[" + index++ + "]";
            if (!child.isJsonPrimitive() || !child.getAsJsonPrimitive().isString()) {
                problems.error(document, childPath, "expected the name of a " + childKind.word()
                        + " as a string, found " + Checks.kindOf(child)
                        + " - a manifest lists names, not objects");
                continue;
            }
            names.add(child.getAsString());
        }
        return names;
    }

    // ------------------------------------------------------------------
    // Reading and listing
    // ------------------------------------------------------------------

    /**
     * Parses one file, reporting a failure with its line and column.
     *
     * <p>Empty rather than throwing, so every caller handles "this file was unreadable" the same way it
     * handles "this file was not there": skip it and say so. The two are different messages and the
     * same consequence, which is why they are not one branch.
     */
    /**
     * Parses one file into a positioned document, outside the walk.
     *
     * <p>For readers that own a file kind the walk does not produce — the reward tables, whose folder
     * is reserved rather than listed. Same parse, same messages: a table with a syntax error is
     * reported at its line like everything else.
     */
    public static Optional<JsonDocument> parseFile(Path path, String display, Problems problems) {
        return parse(path, display, problems);
    }

    private static Optional<JsonDocument> parse(Path path, String display, Problems problems) {
        // A file this process has already read and that has not changed since: the document and the
        // messages it produced, replayed rather than derived again. See ParsedFiles -- including why the
        // messages are the half that must not be skipped.
        ParsedFiles.Stamp stamp = ParsedFiles.stamp(path);
        if (stamp != null) {
            ParsedFiles.Held held = ParsedFiles.held(path, stamp);
            if (held != null) {
                problems.addAll(held.problems());
                return held.document();
            }
        }

        // This file's own messages, collected apart from the load's so that they can be held with it.
        // A `Problems` is keyed by file name, so replaying them adds nothing to the total that a cold
        // load would not also have added.
        Problems mine = new Problems();
        Optional<JsonDocument> document = read(path, display, mine);
        problems.addAll(mine.all());
        if (stamp != null) {
            ParsedFiles.hold(path, stamp, document, mine.all());
        }
        return document;
    }

    /** The read and the parse themselves, with their messages going to the caller's own list. */
    private static Optional<JsonDocument> read(Path path, String display, Problems problems) {
        String text;
        try {
            text = Files.readString(path, StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            problems.add(display, new JsonLocation(1, 1, "$"), DataProblem.Severity.ERROR,
                    "could not be read: " + e.getMessage());
            return Optional.empty();
        }

        try {
            return Optional.of(JsonDocument.parse(display, text));
        }
        catch (JsonParseException e) {
            // The exception's whole message, which already carries the offending line and a caret --
            // the same reason QuestLoader reports e.getMessage() rather than e.location() alone.
            problems.add(display, e.location(), DataProblem.Severity.ERROR, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * The entries of a directory, name-sorted, or null if it could not be listed.
     *
     * <p>Sorted because file-system order is not stable and a load order that shuffles itself between
     * runs is maddening to read — and here it decides the order of the group rows in the book, so an
     * unsorted list would reorder a player's sidebar between two launches.
     */
    private static List<Path> listSorted(Path directory) {
        if (!Files.isDirectory(directory)) {
            return null;
        }
        try (Stream<Path> entries = Files.list(directory)) {
            List<Path> out = new ArrayList<>(entries.toList());
            out.sort(Comparator.comparing(path -> path.getFileName().toString()));
            return out;
        }
        catch (IOException e) {
            return null;
        }
    }

    /** Whether a name is a file this loader will read. Anything else is not content. */
    private static boolean isQuestFile(String name) {
        return name.endsWith(".json");
    }

    /** A path relative to the root, {@code /}-separated, for messages and for the dump. */
    private static String display(Path root, Path path) {
        try {
            return root.relativize(path).toString().replace('\\', '/');
        }
        catch (IllegalArgumentException e) {
            // Not both under the root, which can only happen if the caller passed mismatched paths. A
            // message with an absolute path in it is still more useful than a thrown exception here.
            return path.toString().replace('\\', '/');
        }
    }

    /** A string at a path, or null. Null rather than empty, because "absent" and {@code "\"\""} differ. */
    private static String stringAt(JsonDocument document, String path) {
        JsonElement element = document.get(path).orElse(null);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            return null;
        }
        return element.getAsString();
    }
}
