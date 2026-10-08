package dev.ellipog.tenet.quest;

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
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * What is in {@code config/tenet/quests/}, discovered rather than interpreted.
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
 *   config/tenet/quests/
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
     *
     * <p>There are <b>two spellings</b> of it, and this constant is the stem of both. A quest's aside is
     * the fixed {@code <stem>.json.deleted}, because an undo finds it by that exact name; a chapter's or
     * a group's is numbered — {@code <id>.deleted}, then {@code <id>.deleted.2} — because a structural
     * edit's own steps can name the copy they made. See {@link #isDeletedName}, which has to answer for
     * both: the numbered form is written by this mod's own undo, so a rule that only knew the plain
     * suffix would let the walk read a folder the editor had just put aside.
     */
    public static final String DELETED_SUFFIX = ".deleted";

    /**
     * Whether a single name is a recoverable delete, and so skipped wherever it appears.
     *
     * <p>Both spellings count: {@code <name>.deleted} and the numbered {@code <name>.deleted.2} that
     * {@code QuestStructure.aside} writes rather than overwrite a copy already there. The two are one
     * fact — content that was removed and kept in case — so they have to be one answer, or the walk
     * reads the tombstone as book content and the editor writes it into {@code index.json}.
     */
    public static boolean isDeletedName(String name) {
        if (name == null) {
            return false;
        }
        if (name.endsWith(DELETED_SUFFIX)) {
            return true;
        }
        int at = name.lastIndexOf(DELETED_SUFFIX + ".");
        return at >= 0 && allDigits(name, at + DELETED_SUFFIX.length() + 1);
    }

    /** Whether every character from {@code from} to the end is a decimal digit, and there is one. */
    private static boolean allDigits(String name, int from) {
        if (from >= name.length()) {
            return false;
        }
        for (int i = from; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * Where a set-aside copy of this path goes, without ever overwriting one already there.
     *
     * <h2>Why the name is decided here rather than at each caller</h2>
     *
     * <p>Because {@link #isDeletedName} has to recognise whatever this writes, and the two were written
     * apart: this numbered a second copy — {@code <name>.deleted}, then {@code <name>.deleted.2} — while
     * the skip rule knew only the plain suffix, so a tombstone the editor had just made read as book
     * content. The pair is one fact in two halves, so it lives in one place.
     *
     * <p>Numbering rather than refusing, and rather than replacing: a copy already there is an author's
     * file, and the only operation that cannot lose it is a name that does not collide.
     */
    public static Path asidePath(Path path) {
        Path candidate = path.resolveSibling(path.getFileName() + DELETED_SUFFIX);
        int n = 2;
        while (Files.exists(candidate)) {
            candidate = path.resolveSibling(path.getFileName() + DELETED_SUFFIX + "." + n++);
        }
        return candidate;
    }

    /**
     * The name a set-aside copy comes back as, or null when the name is not one.
     *
     * <p>The inverse of {@link #asidePath}, for a caller that has a tombstone and wants to say what
     * restoring it would make. Both spellings are stripped, and a name that is neither returns null
     * rather than a guess.
     */
    public static String restoredName(String name) {
        if (name == null) {
            return null;
        }
        if (name.endsWith(DELETED_SUFFIX)) {
            return orNull(name.substring(0, name.length() - DELETED_SUFFIX.length()));
        }
        int at = name.lastIndexOf(DELETED_SUFFIX + ".");
        if (at >= 0 && allDigits(name, at + DELETED_SUFFIX.length() + 1)) {
            return orNull(name.substring(0, at));
        }
        return null;
    }

    /** A name that is only the suffix restores to nothing, which is not a name. */
    private static String orNull(String name) {
        return name == null || name.isEmpty() ? null : name;
    }

    /**
     * Every recoverable delete under this root: what is set aside, and what each would come back as.
     *
     * <h2>Why the editor cannot answer this and this can</h2>
     *
     * <p>Because the editor's tree <i>skips</i> tombstones — deliberately, so a deleted chapter is not book
     * content — and a skip is not a listing. So the one thing an author needs after the history is gone (a
     * server restart, a reload, sixty further edits) is the one thing nothing would tell them: that their
     * work is still on disk, under a name with a suffix on the end.
     *
     * <p>Name-sorted, and never descending into a tombstone: a set-aside chapter folder holds its quests
     * inside it, and offering each of those separately would be offering to restore a file into a folder
     * that is itself set aside. The chapter is the thing to restore; its quests come with it.
     */
    public static List<Removed> removedFiles(Path questRoot) {
        if (questRoot == null || !Files.isDirectory(questRoot)) {
            return List.of();
        }
        List<Removed> found = new ArrayList<>();
        for (Path entry : orEmpty(listSorted(questRoot))) {
            String name = entry.getFileName().toString();
            // The tables' folder is reserved rather than book content, and it is read below; the `_` rule
            // means deliberately out of the way, and a tombstone of a note is not an author's work.
            if (DeclaredPaths.isIgnoredName(name) || isReservedName(name)) {
                continue;
            }
            if (isDeletedName(name)) {
                found.add(whatWas(questRoot, entry));
                continue;
            }
            if (!Files.isDirectory(entry)) {
                continue;
            }
            if (Files.isRegularFile(entry.resolve(GROUP_MANIFEST))) {
                for (Path child : orEmpty(listSorted(entry))) {
                    String childName = child.getFileName().toString();
                    if (DeclaredPaths.isIgnoredName(childName) || childName.equals(GROUP_MANIFEST)) {
                        continue;
                    }
                    if (isDeletedName(childName)) {
                        found.add(whatWas(questRoot, child));
                    }
                    else if (Files.isDirectory(child)
                            && Files.isRegularFile(child.resolve(CHAPTER_MANIFEST))) {
                        found.addAll(questTombstones(questRoot, child));
                    }
                }
            }
            else if (Files.isRegularFile(entry.resolve(CHAPTER_MANIFEST))) {
                found.addAll(questTombstones(questRoot, entry));
            }
        }
        for (Path entry : orEmpty(listSorted(questRoot.resolve(REWARD_TABLES_DIRECTORY)))) {
            String name = entry.getFileName().toString();
            String back = restoredName(name);
            if (Files.isRegularFile(entry) && back != null && isQuestFile(back)) {
                found.add(new Removed(display(questRoot, entry), Removed.Kind.TABLE, back, null));
            }
        }
        found.sort(Comparator.comparing(Removed::path));
        return List.copyOf(found);
    }

    /** The quest files set aside inside one live chapter folder. */
    private static List<Removed> questTombstones(Path root, Path chapter) {
        List<Removed> found = new ArrayList<>();
        String holds = chapter.getFileName().toString();
        for (Path entry : orEmpty(listSorted(chapter))) {
            String name = entry.getFileName().toString();
            String back = restoredName(name);
            if (Files.isRegularFile(entry) && back != null && isQuestFile(back)) {
                found.add(new Removed(display(root, entry), Removed.Kind.QUEST, back, holds));
            }
        }
        return found;
    }

    /** What one tombstone was, told by what is inside it. */
    private static Removed whatWas(Path root, Path entry) {
        String name = entry.getFileName().toString();
        String back = restoredName(name);
        Removed.Kind kind = Files.isRegularFile(entry.resolve(GROUP_MANIFEST)) ? Removed.Kind.GROUP
                : Files.isRegularFile(entry.resolve(CHAPTER_MANIFEST)) ? Removed.Kind.CHAPTER
                : Removed.Kind.QUEST;
        return new Removed(display(root, entry), kind, back == null ? name : back, null);
    }

    /**
     * The file a relative name from a listing names, when it is a recoverable delete under this root.
     *
     * <h2>Containment is the point, not a formality</h2>
     *
     * <p>The name arrives from a command or from a payload, and it is about to be <b>moved</b>. A restore
     * that resolved {@code ../../server.properties} would be a file move aimed outside the quest folder by
     * whatever the caller typed — so the normalised result has to stay under the root, the name has to be
     * a tombstone, and the thing it names has to exist. Three questions, and all three have to be yes.
     *
     * @return the absolute path, or null when the name is not a set-aside thing under this root
     */
    public static Path resolveRemoved(Path questRoot, String relative) {
        if (questRoot == null || relative == null || relative.isBlank()) {
            return null;
        }
        Path root = questRoot.toAbsolutePath().normalize();
        Path candidate;
        try {
            candidate = root.resolve(relative).normalize();
        }
        catch (RuntimeException invalid) {
            // An invalid path for this filesystem, which is a refusal rather than a crash in a command.
            return null;
        }
        if (candidate.equals(root) || !candidate.startsWith(root)) {
            return null;
        }
        // **Every folder between the root and the copy has to be live.** A tombstone inside a tombstone —
        // `alpha.deleted/one.deleted` — is a copy whose parent is set aside, so restoring it would move it
        // into a folder every walk skips: the edit reports success, the chapter never appears, and the copy
        // vanishes from `/tenet removed` too, because that listing does not descend a tombstone either. The
        // reserved `reward_tables` folder is deliberately not in this rule: it is where a table tombstone
        // lives, and it is skipped as *book content* rather than as storage.
        for (Path at = candidate.getParent(); at != null && !at.equals(root); at = at.getParent()) {
            String name = at.getFileName() == null ? "" : at.getFileName().toString();
            if (isDeletedName(name) || DeclaredPaths.isIgnoredName(name)) {
                return null;
            }
        }
        if (restoredName(candidate.getFileName().toString()) == null) {
            return null;
        }
        return Files.exists(candidate) ? candidate : null;
    }

    /** A list that may be null, which is how {@link #listSorted} answers a directory it could not read. */
    private static List<Path> orEmpty(List<Path> entries) {
        return entries == null ? List.of() : entries;
    }

    /**
     * The root folder reward tables live in, beside the book rather than inside it.
     *
     * <p>A table is not a sidebar row, not a chapter and not a quest: it is a named roll of rewards a
     * {@code tenet:random}/{@code loot}/{@code all_table}/{@code choice} reward points at. Putting
     * them under {@code index.json} would have meant a fourth entry kind and a table pretending to be
     * book order; the folder is reserved by name instead, the way {@code _schema} is reserved by
     * prefix. Unlike that one, this name carries content, so it is a word rather than an underscore:
     * an author should be able to find it.
     */
    public static final String REWARD_TABLES_DIRECTORY = "reward_tables";

    /**
     * The root folder per-locale quest text lives in, beside the book rather than inside it.
     *
     * <p>One file per locale — {@code lang/en_us.json}, {@code lang/es_es.json} — holding the same
     * shape a resource pack's language file does: a flat object of key to text. Reserved by name for
     * the same reason {@link #REWARD_TABLES_DIRECTORY} is, and it has to be: the root walk reports
     * any entry {@code index.json} does not mention, so a folder that is deliberately not book
     * content must be named here or every pack that ships translations would be told its own
     * {@code lang} folder is a mistake.
     *
     * <p>A word rather than an underscore, like the tables' folder and unlike {@code _schema}: this
     * one carries the author's own words.
     */
    public static final String LANG_DIRECTORY = "lang";

    /** Whether a root entry is Tenet's own storage rather than book content. */
    public static boolean isReservedName(String name) {
        return REWARD_TABLES_DIRECTORY.equals(name) || LANG_DIRECTORY.equals(name);
    }

    /**
     * The locale files at the root, name-sorted. Empty when there is no such folder.
     *
     * <p>Read outside the discovery walk for the reason {@link #rewardTableFiles} gives: a locale is
     * not a {@code Declaration}, and it must not travel through the book's assembly. The walk only
     * needs to know to leave the folder alone.
     *
     * <p>Returns every {@code *.json} that is not a recoverable delete and not ignored by the {@code _}
     * rule. The file's name <i>is</i> the locale id — there is no second place to declare one, and a
     * name that is not a locale a client can ask for is simply never looked up.
     */
    public static List<Path> localeFiles(Path questRoot) {
        Path folder = questRoot.resolve(LANG_DIRECTORY);
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
            if (Files.isRegularFile(entry) && isQuestFile(name) && !isDeletedName(name)
                    && !DeclaredPaths.isIgnoredName(name)) {
                files.add(entry);
            }
        }
        return List.copyOf(files);
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
    // Every id in the tree, readable or not
    // ------------------------------------------------------------------

    /**
     * Every quest id any file under the root declares, whether or not the loader could read it.
     *
     * <h2>Why this is not {@link #discover}</h2>
     *
     * <p>Because discovery answers "what will the loader read" and this has to answer "what names are
     * already taken", and the two differ exactly where a pack is broken — which is when a minted id must
     * not collide. Discovery never parses a quest file its chapter's {@code quests} list does not mention,
     * skips a folder inside a chapter folder with an error, never enters a subtree under a manifest that
     * will not parse, and reports a version-1 flat file as one {@code FLAT_V1} declaration whose quests are
     * not {@code Kind.QUEST} at all. An editor minting against that set hands a new quest an id an existing
     * file already declares, and two quests under one id are one progress record shared by both — the first
     * kept, the second drawn, clickable and never able to advance on its own.
     *
     * <h2>What it skips, and which way the trade errs</h2>
     *
     * <p>The names the loader skips — {@code _}-prefixed, {@code .deleted}, the reserved
     * {@code reward_tables} folder — plus the three manifest names, which declare ids of their own and are
     * not quests. Skipping is the direction that can be wrong: a file left out contributes no id, and a
     * minted id can then collide with it. So the list is exactly the loader's own, and the one case it
     * cannot see — a quest file literally named {@code group.json} — is a file the loader reads only if a
     * chapter's list names it, which is an authoring mistake the load reports.
     *
     * <p>A file that cannot be read contributes nothing rather than failing the scan: the caller is minting
     * a name, and refusing to mint because one file in somebody's pack is unreadable would make the editor
     * unusable. The problem is reported where every other read problem is, at the load. {@link #takenNames}
     * is the mint's own question and does count that file's name — see there.
     */
    public static Set<String> allQuestIds(Path questRoot, Problems problems) {
        return scan(questRoot, problems).ids();
    }

    /**
     * Every name a freshly minted quest id must not take.
     *
     * <h2>Why this is more than {@link #allQuestIds}</h2>
     *
     * <p>{@code allQuestIds} answers "what ids would the loader read", and a mint that stopped there took
     * three names it should not have:
     *
     * <ul>
     *   <li><b>A removed copy's name and id.</b> A tombstone is skipped by every walk, so its id looks free
     *       — and a quest created under it inherits the removed quest's stored progress (progress is keyed
     *       by id, and an id no loaded quest claims is deliberately kept), then cannot be deleted, because
     *       the delete refuses a name a copy already holds. Its declared id matters as much as its file
     *       name: the two differ in a pack a tool named, and the file a mint writes is named from the id.
     *       </li>
     *   <li><b>An alias.</b> Ids and aliases are one namespace per kind — the loader claims both in one map
     *       and the later entry is not loaded at all — so a new quest whose id is another quest's former id
     *       is a quest that never appears. The panel's {@code aliases} row is editable, so this is one
     *       typed alias and one press away.</li>
     *   <li><b>An unreadable file's name.</b> Its declared id cannot be known, but the file's own name can,
     *       and that is the name a mint would write over.</li>
     * </ul>
     *
     * <p>Aliases of <i>chapters and groups</i> are not here: they are a different namespace, and
     * {@code QuestStructure}'s own name check is where those are claimed.
     */
    public static Set<String> takenNames(Path questRoot, Problems problems) {
        Scan scan = scan(questRoot, problems);
        Set<String> taken = new LinkedHashSet<>(scan.ids());
        taken.addAll(scan.aliases());
        taken.addAll(scan.asides());
        taken.addAll(scan.unreadable());
        return taken;
    }

    /**
     * What one walk of the tree found, in the four sets a mint cares about.
     *
     * <p>One walk rather than four: they are the same listing read for different questions, and the sets
     * have to agree about which files were looked at, or a mint would answer from a different tree than the
     * one it just read.
     *
     * @param ids        what {@link #allQuestIds} returns — the loader's own vocabulary
     * @param aliases    every {@code aliases} entry a readable quest file declares
     * @param asides     the file name and the declared id of every set-aside quest file
     * @param unreadable the file name of every quest file that would not parse
     */
    private record Scan(Set<String> ids, Set<String> aliases, Set<String> asides, Set<String> unreadable) {
    }

    /** The one walk. See {@link #allQuestIds} and {@link #takenNames} for what each set is for. */
    private static Scan scan(Path questRoot, Problems problems) {
        Set<String> ids = new LinkedHashSet<>();
        Set<String> aliases = new LinkedHashSet<>();
        Set<String> asides = new LinkedHashSet<>();
        Set<String> unreadable = new LinkedHashSet<>();
        if (questRoot == null || !Files.isDirectory(questRoot)) {
            return new Scan(Set.of(), Set.of(), Set.of(), Set.of());
        }
        try {
            Files.walkFileTree(questRoot, new SimpleFileVisitor<Path>() {

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
                    if (dir.equals(questRoot)) {
                        return FileVisitResult.CONTINUE;
                    }
                    String name = dir.getFileName().toString();
                    return DeclaredPaths.isIgnoredName(name) || isDeletedName(name) || isReservedName(name)
                            ? FileVisitResult.SKIP_SUBTREE
                            : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    String name = file.getFileName().toString();
                    if (!attributes.isRegularFile() || DeclaredPaths.isIgnoredName(name)) {
                        return FileVisitResult.CONTINUE;
                    }
                    // **The tombstone is asked about before `isQuestFile`.** A set-aside file does not end
                    // in `.json` — `two.json.deleted` does not — so the loader's own "is this content" test
                    // answers no, and a branch placed after it is a branch that never runs. It was, and the
                    // two tests below it failed for exactly that reason.
                    if (isDeletedName(name)) {
                        // A set-aside quest file: no live id, but a name a mint must not take. The `_` rule
                        // was asked first, so a tombstone of a note is left out, exactly as the listing
                        // leaves it out.
                        String back = restoredName(name);
                        if (back != null && isQuestFile(back)) {
                            asides.add(stemOf(back));
                            parse(file, display(questRoot, file), problems).ifPresent(document -> {
                                String declared = stringAt(document, "$.id");
                                if (declared != null && !declared.isBlank()) {
                                    asides.add(declared);
                                }
                            });
                        }
                        return FileVisitResult.CONTINUE;
                    }
                    if (!isQuestFile(name) || isManifestName(name)) {
                        return FileVisitResult.CONTINUE;
                    }
                    Optional<JsonDocument> parsed = parse(file, display(questRoot, file), problems);
                    if (parsed.isPresent()) {
                        collectIds(parsed.get(), name, ids, aliases);
                    }
                    else {
                        // Its declared id cannot be known; its name can, and the name is what a mint would
                        // write over.
                        unreadable.add(stemOf(name));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException failure) {
                    // Unreadable, so it declares nothing this can see. Not reported here: the load reads
                    // the same file and reports it at its own line, and a second message about one
                    // unreadable file would be two.
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        catch (IOException e) {
            problems.add(display(questRoot, questRoot), new JsonLocation(1, 1, "$"),
                    DataProblem.Severity.WARNING, "the quest folder could not be walked completely ("
                            + e.getMessage() + "), so some ids are not known to the editor");
        }
        return new Scan(ids, aliases, asides, unreadable);
    }

    /**
     * The quest ids one file declares: its own, or the ones nested inside a version-1 file — and every
     * alias they answer to.
     *
     * <p>A version-1 flat file is a whole tree in one document —
     * {@code chapterGroups[].chapters[].quests[]} — so its root declares no {@code id} at all, and reading
     * only {@code $.id} would see none of the quests inside it. That is the case that matters most here:
     * a pack converted from another mod is exactly where the flat layout and a freshly minted id meet. The
     * paths come from {@link QuestValidator}, which is where this format's paths are written down.
     *
     * <p>The aliases travel with the ids because the loader claims them in one namespace: a mint that took
     * an alias produced a quest that does not load. See {@link #takenNames}.
     */
    private static void collectIds(JsonDocument document, String fileName, Set<String> ids,
                                   Set<String> aliases) {
        JsonElement groups = document.get("$.chapterGroups").orElse(null);
        if (groups != null && groups.isJsonArray()) {
            for (int g = 0; g < groups.getAsJsonArray().size(); g++) {
                JsonElement chapters = document.get(QuestValidator.groupPath(g) + ".chapters").orElse(null);
                if (chapters == null || !chapters.isJsonArray()) {
                    continue;
                }
                for (int c = 0; c < chapters.getAsJsonArray().size(); c++) {
                    JsonElement quests = document.get(QuestValidator.chapterPath(g, c) + ".quests")
                            .orElse(null);
                    if (quests == null || !quests.isJsonArray()) {
                        continue;
                    }
                    for (int q = 0; q < quests.getAsJsonArray().size(); q++) {
                        String at = QuestValidator.questPath(g, c, q);
                        addId(document.get(at + ".id").orElse(null), ids);
                        addAliases(document, at + ".aliases", aliases);
                    }
                }
            }
            return;
        }
        // A file per quest: the id it declares, or its file name — which is the vocabulary the editor keys
        // an id-less file by as well, so a create that ignored the stem could still land on one.
        String declared = stringAt(document, "$.id");
        ids.add(declared == null || declared.isBlank() ? stemOf(fileName) : declared);
        addAliases(document, "$.aliases", aliases);
    }

    /** Every name an {@code aliases} array holds, when it is one. */
    private static void addAliases(JsonDocument document, String path, Set<String> aliases) {
        JsonElement list = document.get(path).orElse(null);
        if (list == null || !list.isJsonArray()) {
            return;
        }
        for (JsonElement alias : list.getAsJsonArray()) {
            addId(alias, aliases);
        }
    }

    /** One id, if it is a string worth remembering. */
    private static void addId(JsonElement element, Set<String> ids) {
        if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            String id = element.getAsString();
            if (!id.isBlank()) {
                ids.add(id);
            }
        }
    }

    /** Whether a name is one of the three manifests, whose {@code id} is not a quest's. */
    private static boolean isManifestName(String name) {
        return GROUP_MANIFEST.equals(name) || CHAPTER_MANIFEST.equals(name) || INDEX_MANIFEST.equals(name);
    }

    /** A quest file's name without its suffix. Only called once {@link #isQuestFile} has said yes. */
    private static String stemOf(String name) {
        return name.substring(0, name.length() - ".json".length());
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
        // True once some walk has read the root: the manifest's when it declares one, the folder names'
        // when there is not one -- or when there is one and it cannot say what the root is, which is the
        // case this arrangement exists for. That last case used to walk nothing at all and empty the whole
        // book; see `discoverIndexed`.
        boolean rootRead = false;
        if (Files.isRegularFile(indexPath)) {
            rootRead = discoverIndexed(questRoot, indexPath, entries, declarations, problems, examined);
        }
        if (!rootRead) {
            walkRootByFolderName(questRoot, entries, declarations, problems, examined);
        }

        return new Discovery(List.copyOf(declarations), problems, examined[0]);
    }

    /**
     * The root read by folder name: every folder is a group, every flat file is a version-1 file.
     *
     * <p>This is the whole of what the loader did before {@code index.json} existed, and it is now two
     * callers rather than one: a tree with no manifest, and a tree whose manifest cannot say what the
     * root is. Keeping it one method is the point -- "no index" has to mean one thing, or the same
     * directory would load differently depending on <i>how</i> its manifest came to be unreadable.
     *
     * <p>A root chapter is the one thing it cannot serve, and deliberately: with no readable manifest
     * there is nothing left that says a folder at the root is a chapter rather than a malformed group,
     * so it is reported as "not a chapter group" and does not load. The shipped example has no
     * {@code index.json} at all, so it is the shape this method is normally handed.
     */
    private static void walkRootByFolderName(Path questRoot, List<Path> entries, List<Declaration> out,
                                             Problems problems, int[] examined) {
        for (Path entry : entries) {
            String name = entry.getFileName().toString();
            if (name.equals(INDEX_MANIFEST)) {
                // Only reachable from the fallback, where this file has already been read and reported:
                // without this it is a root `.json` file like any other, so the walk reads it a second
                // time as a version-1 file and reports the same parse failure twice. It is not book
                // content in either reading.
                continue;
            }
            if (DeclaredPaths.isIgnoredName(name) || isDeletedName(name) || isReservedName(name)) {
                // `_schema/` and every other underscore-prefixed name at the root, anything a
                // recoverable delete left behind, and the reward tables' folder -- which is content,
                // just not the book's. See the class note.
                continue;
            }
            if (Files.isDirectory(entry) && !Files.isSymbolicLink(entry)) {
                discoverGroup(questRoot, entry, out, problems, examined);
            }
            else if (isQuestFile(name)) {
                discoverFlatFile(questRoot, entry, out, problems, examined);
            }
        }
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
     *
     * <h2>Returns whether it read the root, and that is the answer to "the file is broken"</h2>
     *
     * <p>A manifest that cannot be read, or that does not declare a usable {@code entries} list, cannot
     * say what the top level of the book is — so the caller reads the root the way it reads a tree with
     * no manifest at all, and this returns {@code false} to say so. It used to return with nothing
     * walked, which meant <b>one typo in this one file emptied the whole book</b>: every group, every
     * chapter and every quest, live, on the next reload, while every other file in the loader fails
     * open. The book's contents are the one thing that should not depend on this file being valid, and
     * absence has always been a supported state for it -- a tree with no {@code index.json} is read by
     * folder name, and the shipped example is exactly that.
     *
     * <p><b>What is not a fallback:</b> entries that are declared and individually fail to resolve. That
     * is a per-entry fault the walk already reports and skips, and falling back there would load content
     * the manifest deliberately left out.
     */
    private static boolean discoverIndexed(Path root, Path indexPath, List<Path> entries,
                                           List<Declaration> out, Problems problems, int[] examined) {
        String indexDisplay = display(root, indexPath);
        examined[0]++;
        Optional<JsonDocument> parsed = parse(indexPath, indexDisplay, problems);
        if (parsed.isEmpty()) {
            // The parse failure is already reported against this file, at its own line.
            reportIndexFallback(indexDisplay, problems);
            return false;
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
            reportIndexFallback(indexDisplay, problems);
            return false;
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
        return true;
    }

    /**
     * What the author is told when the root manifest could not say what the root is.
     *
     * <p>A second message rather than one, because they are two facts: what is wrong with the file
     * (reported where it was found, against this file at its own line) and what was done about it. The
     * second is the one that stops the reload reading as "my whole book is gone" -- the tree is there
     * and it loaded, in a different order, with anything this file would have excluded included.
     *
     * <p>Reported even though the fallback succeeds, for the reason the loader reports anything: a load
     * that quietly reads a tree differently from how its own manifest describes it is a load whose
     * author has no way to learn the manifest is being ignored.
     */
    private static void reportIndexFallback(String indexDisplay, Problems problems) {
        problems.add(indexDisplay, new JsonLocation(1, 1, "$"), DataProblem.Severity.WARNING,
                "this file does not say what the root of the book is, so the tree is being read as if it"
                        + " were not here: every folder at the root is a chapter group, in folder-name"
                        + " order, and anything this file would have left out of the book is loaded"
                        + " instead. A chapter declared only by this file does not load, because nothing"
                        + " left says that folder is one."
                        + "\n    fix this file and reload to get the order and the contents it declares.");
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
                // **Against the folder, not against this manifest**, and that is the whole of the fix
                // rather than a tidiness. `QuestLoader.assemble` refuses a declaration whose own file
                // carries an error -- so an error attached to `group.json` threw the group away, and with
                // it every chapter the list *did* name and every quest underneath them. One stray folder
                // cost an author their entire group, which is what a pack converted from another mod looks
                // like when a folder gets copied into itself.
                //
                // The sibling branch above, for a stray file, already reports against the thing itself.
                // The two now agree, and the severity is unchanged: the folder really will never load, and
                // saying so is the point of the message.
                problems.add(display(root, entry), new JsonLocation(1, 1, "$"), DataProblem.Severity.ERROR,
                        "the folder \"" + name + "\" is here, and this group's \"chapters\" list does not"
                                + " mention it - so its quests will never load. Add \"" + name
                                + "\" to \"chapters\", or prefix the folder with \"_\" to leave it out"
                                + " deliberately.");
            }
        }

        // Declared order, because that is the author's order and the only thing that can express it.
        // A name that did not resolve was reported and is skipped here, and the rest of the group still
        // loads -- which is the difference between one bad reference and a dead group, and it is now
        // true of the *loader* as well as of this walk. It was not: the failure below used to be
        // reported against `group.json`, and `QuestLoader.assemble` refuses a declaration whose own file
        // carries an error, so one chapter folder deleted by hand took every chapter the list did name
        // with it. The mirror case -- a folder that is there and unlisted -- carries the same fix and
        // the same reasoning; see `QuestLoaderTest`.
        for (String name : declared) {
            DeclaredPaths.Resolved resolved = DeclaredPaths.resolveSibling(
                    folder, name, DeclaredPaths.Kind.DIRECTORY);
            if (!resolved.ok()) {
                problems.add(unresolvedDisplay(root, document, name, resolved), new JsonLocation(1, 1, "$"),
                        DataProblem.Severity.ERROR, resolved.problem());
                continue;
            }
            discoverChapter(root, resolved.path(), manifestDisplay, out, problems, examined);
        }
    }

    /**
     * Where a declared child that could not be resolved is reported.
     *
     * <h2>The child, not the manifest that named it</h2>
     *
     * <p>A resolution failure is a fact about a name and the thing it should have resolved to, so it
     * belongs to the name. Reporting it against the declaring manifest is what made it fatal: the
     * loader gates every declaration on "is there an error against this file", so an error attached to
     * <code>chapter.json</code> refused the chapter and dropped every quest its list did name, and one
     * attached to <code>group.json</code> did the same a level up. A deleted file is the ordinary way
     * in -- Explorer, a bad merge, a rename done in one place -- and the cost was a chapter or a group
     * rather than the one thing that is actually missing.
     *
     * <p>This is the same fix, in the same shape, that the unlisted case already carries one branch
     * over, and the assertion that keeps it honest is in {@code QuestFilesTest}: the problem's
     * <i>file</i> is the child, never the manifest.
     *
     * @param root      the quest root, for the display name
     * @param declaring the manifest that named the child, used only when there is nothing else to name
     * @param declared  the name exactly as the manifest spelled it
     * @param resolved  the failed resolution: its path is the place the name would have resolved to, and
     *                  is null when the name itself was unusable
     */
    private static String unresolvedDisplay(Path root, JsonDocument declaring, String declared,
                                            DeclaredPaths.Resolved resolved) {
        if (resolved.path() != null) {
            // The common case: the name is fine and there is nothing there (or there is the wrong kind
            // of thing). Naming it is what makes the message actionable, and it is what the loader's
            // per-file gate then reads.
            return display(root, resolved.path());
        }
        // The name itself was unusable -- a separator, a `..`, an `_`-prefixed name, an absolute path.
        // The name as written is the thing to fix, and every message `problemWithName` produces quotes
        // it, so it is the honest token. A blank name has nothing to quote, and that is the one case
        // where this reports against a file that does load: an empty string names nothing, so there is
        // no child to blame.
        return declared == null || declared.isBlank() ? declaring.name() : declared;
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
                // Against the file, for the reason the group branch above gives: an error on `chapter.json`
                // makes the loader refuse the chapter, and a chapter refused takes every quest it lists
                // with it. One unlisted file must not cost an author a chapter of forty.
                problems.add(display(root, entry), new JsonLocation(1, 1, "$"), DataProblem.Severity.ERROR,
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
                // **Reported against the name that did not resolve, not against this manifest**, and
                // the difference is a whole chapter. `QuestLoader.assemble` refuses a declaration whose
                // own file carries an error, so an error against `chapter.json` took every quest its
                // list *did* name with it: one file deleted by hand in Explorer cost an author the
                // other thirty-nine. The mirror case -- a quest file that is there and unlisted -- was
                // fixed the same way and says the same thing; see `QuestLoaderTest`.
                problems.add(unresolvedDisplay(root, document, name, resolved), new JsonLocation(1, 1, "$"),
                        DataProblem.Severity.ERROR, resolved.problem());
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
