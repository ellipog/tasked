package dev.ellipog.tasked.quest;

import dev.ellipog.armature.api.data.DataProblem;
import dev.ellipog.armature.api.data.JsonDocument;
import dev.ellipog.armature.api.data.Problems;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Every loaded quest, indexed, with the cross-file checks done.
 *
 * <h2>Why this is separate from the validator</h2>
 *
 * <p>Because it needs to see all of them. A file on its own cannot know that {@code punch_a_tree}
 * already exists in another file, or that a {@code dependsOn} points at a quest that was deleted.
 * That is a different job from "is this file well-formed", and keeping them apart means the validator
 * stays a per-file check while this runs once over everything.
 *
 * <p>Duplicate identifiers are reported <b>only</b> here, including duplicates within one file. The
 * validator has no cross-quest state, so it cannot see them, and having exactly one place that
 * decides whether an id is taken means there is never a case where two checks disagree about it.
 *
 * <h2>Identifiers, and why aliases matter</h2>
 *
 * <p>A quest is looked up by id <b>or alias</b>, everywhere: in {@code dependsOn}, in commands, in the
 * editor. That is what makes renaming a quest safe. Progress is stored against whatever id the quest
 * had when a player completed it, so without aliases a rename silently discards every player's
 * progress through that quest — a mistake FTB Quests lets you make.
 *
 * <p>So there is one table keyed by both, and a clash between them is an error: if one quest's id
 * collides with another's alias, a lookup is ambiguous, and ambiguity here means somebody's progress
 * lands on the wrong quest.
 */
public final class QuestIndex {

    private final List<LoadedQuestFile> files;
    private final Map<String, QuestEntry> byIdentifier;
    private final Map<String, ChapterEntry> chaptersByIdentifier;
    private final Map<String, GroupEntry> groupsByIdentifier;

    /** A quest, and where it came from. */
    public record QuestEntry(String groupId, String chapterId, Quest quest,
                             String file, JsonDocument document, String path) {

        /** Where this quest is, for a message: {@code quests/01_stone_age.json:14:9}. */
        public String location() {
            return file + ":" + document.nearestLocation(path);
        }
    }

    /** A chapter, and where it came from. */
    public record ChapterEntry(String groupId, Chapter chapter, String file, JsonDocument document, String path) {
        public String location() {
            return file + ":" + document.nearestLocation(path);
        }
    }

    /** A chapter group, and where it came from. */
    public record GroupEntry(ChapterGroup group, String file, JsonDocument document, String path) {
        public String location() {
            return file + ":" + document.nearestLocation(path);
        }
    }

    private QuestIndex(List<LoadedQuestFile> files,
                       Map<String, QuestEntry> byIdentifier,
                       Map<String, ChapterEntry> chaptersByIdentifier,
                       Map<String, GroupEntry> groupsByIdentifier) {
        this.files = List.copyOf(files);
        this.byIdentifier = Map.copyOf(byIdentifier);
        this.chaptersByIdentifier = Map.copyOf(chaptersByIdentifier);
        this.groupsByIdentifier = Map.copyOf(groupsByIdentifier);
    }

    /**
     * Builds the index, reporting duplicate identifiers and unresolvable references into
     * {@code problems}.
     *
     * <p>Files that failed validation are not here — the loader filters them out first. So every quest
     * in this index decoded, and a reference failing to resolve means the reference is wrong rather
     * than that the target failed to load. That distinction is what makes the error message say
     * "no such quest" with confidence.
     */
    public static QuestIndex build(List<LoadedQuestFile> files, Problems problems) {
        Map<String, QuestEntry> quests = new LinkedHashMap<>();
        Map<String, ChapterEntry> chapters = new LinkedHashMap<>();
        Map<String, GroupEntry> groups = new LinkedHashMap<>();

        for (LoadedQuestFile loaded : files) {
            JsonDocument document = loaded.document();

            for (int g = 0; g < loaded.file().chapterGroups().size(); g++) {
                ChapterGroup group = loaded.file().chapterGroups().get(g);
                String groupPath = QuestValidator.groupPath(g);

                claimIdentifier(groups, quests, chapters, group.id(),
                        new GroupEntry(group, loaded.displayName(), document, groupPath),
                        "chapter group", document, groupPath + ".id", problems);
                for (String alias : group.aliases()) {
                    claimAlias(groups, quests, chapters, alias,
                            new GroupEntry(group, loaded.displayName(), document, groupPath),
                            group.id(), "chapter group", document, groupPath + ".aliases", problems);
                }

                for (int c = 0; c < group.chapters().size(); c++) {
                    Chapter chapter = group.chapters().get(c);
                    String chapterPath = QuestValidator.chapterPath(g, c);
                    ChapterEntry chapterEntry = new ChapterEntry(group.id(), chapter,
                            loaded.displayName(), document, chapterPath);

                    claimIdentifier(groups, quests, chapters, chapter.id(), chapterEntry,
                            "chapter", document, chapterPath + ".id", problems);
                    for (String alias : chapter.aliases()) {
                        claimAlias(groups, quests, chapters, alias, chapterEntry, chapter.id(),
                                "chapter", document, chapterPath + ".aliases", problems);
                    }

                    for (int q = 0; q < chapter.quests().size(); q++) {
                        Quest quest = chapter.quests().get(q);
                        String questPath = QuestValidator.questPath(g, c, q);
                        QuestEntry questEntry = new QuestEntry(group.id(), chapter.id(), quest,
                                loaded.displayName(), document, questPath);

                        claimIdentifier(groups, quests, chapters, quest.id(), questEntry,
                                "quest", document, questPath + ".id", problems);
                        for (String alias : quest.aliases()) {
                            claimAlias(groups, quests, chapters, alias, questEntry, quest.id(),
                                    "quest", document, questPath + ".aliases", problems);
                        }

                        checkSelfDependency(document, questPath, quest, problems);
                        checkPlacement(document, questPath, quest, problems);
                    }
                }
            }
        }

        QuestIndex index = new QuestIndex(files, quests, chapters, groups);
        index.checkDependencies(problems);
        index.checkDuplicatePositions(problems);
        // Not the same question as checkDuplicatePositions: two quests at 0,0 are stacked, two at 64,0
        // are *crowded* -- each is fine on its own and the two together cannot both show a title.
        index.checkCrowdedRows(problems);
        return index;
    }

    // ------------------------------------------------------------------
    // Claiming identifiers
    // ------------------------------------------------------------------

    /**
     * Takes an identifier for an entry, or reports that it is already taken.
     *
     * <p>The three tables are separate because an id being reused across kinds is fine — a chapter
     * and a quest may both be called {@code stone_age} — while an id being reused within a kind is
     * not. Passing all three keeps that distinction in one place instead of three.
     */
    private static void claimIdentifier(Map<String, GroupEntry> groups,
                                        Map<String, QuestEntry> quests,
                                        Map<String, ChapterEntry> chapters,
                                        String identifier, Object entry, String what,
                                        JsonDocument document, String path, Problems problems) {
        Object existing = lookup(groups, quests, chapters, kindOf(entry), identifier);
        if (existing == null) {
            put(groups, quests, chapters, kindOf(entry), identifier, entry);
            return;
        }
        if (existing.equals(entry)) {
            // The same object claimed twice, which cannot happen from a walk over distinct elements.
            return;
        }
        problems.add(document.name(), document.nearestLocation(path), DataProblem.Severity.ERROR,
                "duplicate " + what + " id \"" + identifier + "\" - already used by " + describe(existing));
    }

    private static void claimAlias(Map<String, GroupEntry> groups,
                                   Map<String, QuestEntry> quests,
                                   Map<String, ChapterEntry> chapters,
                                   String alias, Object entry, String canonicalId, String what,
                                   JsonDocument document, String path, Problems problems) {
        Object existing = lookup(groups, quests, chapters, kindOf(entry), alias);
        if (existing == null) {
            put(groups, quests, chapters, kindOf(entry), alias, entry);
            return;
        }
        if (existing.equals(entry)) {
            problems.add(document.name(), document.nearestLocation(path), DataProblem.Severity.ERROR,
                    "the alias \"" + alias + "\" is declared twice on the same " + what);
            return;
        }
        problems.add(document.name(), document.nearestLocation(path), DataProblem.Severity.ERROR,
                "the alias \"" + alias + "\" is already used by another " + what + " ("
                        + describe(existing) + "). An alias has to be unique within its kind - a lookup of \""
                        + alias + "\" would otherwise be ambiguous, and player progress could land on the "
                        + "wrong one.");
    }

    private enum Kind { GROUP, CHAPTER, QUEST }

    private static Kind kindOf(Object entry) {
        if (entry instanceof GroupEntry) {
            return Kind.GROUP;
        }
        if (entry instanceof ChapterEntry) {
            return Kind.CHAPTER;
        }
        return Kind.QUEST;
    }

    private static Object lookup(Map<String, GroupEntry> groups, Map<String, QuestEntry> quests,
                                 Map<String, ChapterEntry> chapters, Kind kind, String identifier) {
        return switch (kind) {
            case GROUP -> groups.get(identifier);
            case CHAPTER -> chapters.get(identifier);
            case QUEST -> quests.get(identifier);
        };
    }

    private static void put(Map<String, GroupEntry> groups, Map<String, QuestEntry> quests,
                            Map<String, ChapterEntry> chapters, Kind kind, String identifier, Object entry) {
        switch (kind) {
            case GROUP -> groups.put(identifier, (GroupEntry) entry);
            case CHAPTER -> chapters.put(identifier, (ChapterEntry) entry);
            case QUEST -> quests.put(identifier, (QuestEntry) entry);
        }
    }

    private static String describe(Object entry) {
        if (entry instanceof QuestEntry quest) {
            return "the quest \"" + quest.quest().id() + "\" in " + quest.file()
                    + ":" + quest.document().nearestLocation(quest.path());
        }
        if (entry instanceof ChapterEntry chapter) {
            return "the chapter \"" + chapter.chapter().id() + "\" in " + chapter.file()
                    + ":" + chapter.document().nearestLocation(chapter.path());
        }
        if (entry instanceof GroupEntry group) {
            return "the chapter group \"" + group.group().id() + "\" in " + group.file()
                    + ":" + group.document().nearestLocation(group.path());
        }
        return String.valueOf(entry);
    }

    // ------------------------------------------------------------------
    // Cross-file checks
    // ------------------------------------------------------------------

    /** Every {@code dependsOn} must point at something that exists. */
    private void checkDependencies(Problems problems) {
        for (QuestEntry entry : quests()) {
            for (QuestRef dependency : entry.quest().dependencies()) {
                if (byIdentifier.containsKey(dependency.id())) {
                    continue;
                }
                Optional<String> suggestion = nearestIdentifier(dependency.id());
                problems.error(entry.document(), entry.path() + ".dependsOn",
                        "no quest with id or alias \"" + dependency.id() + "\" exists"
                                + suggestion.map(s -> " - did you mean \"" + s + "\"?").orElse("")
                                + "\n    a dependency that resolves to nothing means this quest can never be unlocked");
            }
        }
    }

    /** A quest cannot depend on itself: it would never unlock. */
    private static void checkSelfDependency(JsonDocument document, String questPath, Quest quest,
                                            Problems problems) {
        for (QuestRef dependency : quest.dependencies()) {
            if (quest.matches(dependency.id())) {
                problems.error(document, questPath + ".dependsOn", "this quest depends on itself (\""
                        + dependency.id() + "\"), so it can never be started");
            }
        }
    }

    private static void checkPlacement(JsonDocument document, String questPath, Quest quest,
                                       Problems problems) {
        // A quest with nothing to do and nothing to give is almost always one someone started and
        // did not finish writing.
        if (quest.tasks().isEmpty() && quest.rewards().isEmpty()) {
            problems.warn(document, questPath,
                    "this quest has no tasks and no rewards, so there is nothing to do in it");
        }
        if (quest.minRequired() > quest.dependencies().size()) {
            problems.error(document, questPath + ".minRequired", "minRequired is " + quest.minRequired()
                    + " but there are only " + quest.dependencies().size()
                    + " dependencies, so this quest can never be unlocked");
        }
    }

    /**
     * Two quests at the same coordinates.
     *
     * <p>A warning, not an error: the questline still works, and the overlap may be deliberate — a
     * quest hidden behind another until it unlocks. But it is far more often a copy-paste, and finding
     * it by eye on a canvas where two nodes sit exactly on top of each other is impossible.
     */
    private void checkDuplicatePositions(Problems problems) {
        Map<String, QuestEntry> taken = new LinkedHashMap<>();
        for (QuestEntry entry : quests()) {
            QuestLayout layout = entry.quest().layout();
            String key = layout.x() + "," + layout.y();
            QuestEntry other = taken.putIfAbsent(key, entry);
            if (other != null) {
                problems.warn(entry.document(), entry.path(),
                        "another quest (\"" + other.quest().id() + "\") is at the same position "
                                + layout.x() + "," + layout.y() + ", so one will be drawn over the other");
            }
        }
    }

    /**
     * The narrowest column spacing at which every label can be drawn in full.
     *
     * <p>From the client: a node label is capped at 120px wide and needs a few pixels of gap either
     * side. Below that the book still works — it truncates by width and drops a label that would sit
     * over another node — but a questline authored tighter than this will read as cramped, so the
     * author is told at load rather than at play.
     */
    private static final int MIN_LABEL_SPACING = 128;

    /**
     * A rough width per character, for deciding whether a title will be truncated.
     *
     * <p>An estimate, and knowingly so. The alternative is a font metric, and this check runs on the
     * server where there is no font — so an accurate answer is not available at the moment the answer
     * is useful. Six pixels is a fair average for Minecraft's default font at GUI scale 1.
     *
     * <p>An estimate is acceptable here because of which way it can be wrong. This is a warning about
     * readability, not an error: a false negative means an author finds out by looking, which is what
     * happened before this check existed. A false positive would be noise, which is why the threshold
     * is generous rather than tight.
     */
    private static final int APPROX_CHAR_WIDTH = 6;

    /**
     * Quests placed so close together in a row that their titles cannot both be drawn.
     *
     * <h2>Why this is a warning and not merely a matter of taste</h2>
     *
     * <p>Because it produced a bug that took a while to identify. The shipped example questline had
     * nodes 64 pixels apart carrying titles around 90 pixels wide, so three labels centred on three
     * nodes 64px apart were drawn straight through each other. On screen that is
     * <em>"Punch a SomewherStone To…"</em> — which reads as a corrupted string, or as a font problem,
     * or as a renderer bug. It is none of those: it is three correct labels in a space that cannot hold
     * them.
     *
     * <p>The client now copes (it truncates to the measured room and skips a label that would overlap a
     * node), so this is no longer a rendering fault. It is still a layout that will not show what the
     * author wrote, and the useful moment to say so is when the file loads.
     *
     * <p>Only adjacent pairs in the same row are compared, because those are the only pairs that can
     * collide: a label sits below its node, so labels on different rows never meet.
     */
    private void checkCrowdedRows(Problems problems) {
        Map<String, List<QuestEntry>> rows = new LinkedHashMap<>();
        for (QuestEntry entry : quests()) {
            QuestLayout layout = entry.quest().layout();
            // Keyed by chapter as well as row: two chapters are different canvases, so a quest in each
            // at y=0 cannot collide with the other.
            rows.computeIfAbsent(entry.groupId() + "/" + entry.chapterId() + "/" + layout.y(),
                    key -> new ArrayList<>()).add(entry);
        }

        for (List<QuestEntry> row : rows.values()) {
            if (row.size() < 2) {
                continue;
            }
            List<QuestEntry> leftToRight = new ArrayList<>(row);
            // The lambda's parameter is typed explicitly. With inference the target type is not
            // settled at this point, and `comparingInt(entry -> ...)` fails to infer T -- which reads
            // as a mysterious "cannot infer type-variable(s) T" on a line with nothing wrong with it.
            leftToRight.sort(Comparator.comparingInt((QuestEntry entry) -> entry.quest().layout().x()));

            for (int i = 1; i < leftToRight.size(); i++) {
                QuestEntry left = leftToRight.get(i - 1);
                QuestEntry right = leftToRight.get(i);

                int gap = right.quest().layout().x() - left.quest().layout().x();
                if (gap == 0 || gap >= MIN_LABEL_SPACING) {
                    // 0 is a genuine duplicate, which checkDuplicatePositions already reports -- saying
                    // it twice would be noise, and a line of text where two nodes overlap is not the
                    // interesting part of that problem.
                    continue;
                }

                String leftTitle = left.quest().title().value();
                String rightTitle = right.quest().title().value();
                int widest = Math.max(approxWidth(leftTitle), approxWidth(rightTitle));
                if (widest <= gap - 8) {
                    continue;
                }

                problems.warn(right.document(), right.path(),
                        "this quest and \"" + left.quest().id() + "\" are " + gap
                                + " pixels apart in the same row, but their titles want about " + widest
                                + " - the quest book draws one label per node under the node, so these two "
                                + "will overlap and run together\n"
                                + "    the book needs about " + MIN_LABEL_SPACING
                                + " between two quests in a row for both titles to be shown in full"
                                + " (label widths are estimated, so this is approximate)");
            }
        }
    }

    /** A rough pixel width for a title, for {@link #checkCrowdedRows}. */
    private static int approxWidth(String title) {
        return title.length() * APPROX_CHAR_WIDTH;
    }

    /** Levenshtein over the known identifiers, for a "did you mean" on an unresolved dependency. */
    private Optional<String> nearestIdentifier(String missed) {
        String best = null;
        int bestDistance = 3;
        for (String candidate : byIdentifier.keySet()) {
            int distance = editDistance(missed.toLowerCase(Locale.ROOT), candidate.toLowerCase(Locale.ROOT));
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return Optional.ofNullable(best);
    }

    private static int editDistance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), substitution);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    // ------------------------------------------------------------------
    // Lookup
    // ------------------------------------------------------------------

    /** A quest by id or alias. */
    public Optional<QuestEntry> quest(String identifier) {
        return Optional.ofNullable(byIdentifier.get(identifier));
    }

    /** A chapter by id or alias. */
    public Optional<ChapterEntry> chapter(String identifier) {
        return Optional.ofNullable(chaptersByIdentifier.get(identifier));
    }

    /** A chapter group by id or alias. */
    public Optional<GroupEntry> group(String identifier) {
        return Optional.ofNullable(groupsByIdentifier.get(identifier));
    }

    /**
     * Every quest, once each, in file order.
     *
     * <p>The lookup table holds an entry under a quest's id <i>and</i> each of its aliases, so walking
     * it directly would visit a quest once per name and report its dependencies once per name. This
     * walks the files instead, which is the only ordering that is stable.
     */
    public List<QuestEntry> quests() {
        List<QuestEntry> out = new ArrayList<>();
        for (LoadedQuestFile loaded : files) {
            for (int g = 0; g < loaded.file().chapterGroups().size(); g++) {
                ChapterGroup group = loaded.file().chapterGroups().get(g);
                for (int c = 0; c < group.chapters().size(); c++) {
                    Chapter chapter = group.chapters().get(c);
                    for (int q = 0; q < chapter.quests().size(); q++) {
                        Quest quest = chapter.quests().get(q);
                        out.add(new QuestEntry(group.id(), chapter.id(), quest, loaded.displayName(),
                                loaded.document(), QuestValidator.questPath(g, c, q)));
                    }
                }
            }
        }
        return List.copyOf(out);
    }

    public List<LoadedQuestFile> files() {
        return files;
    }

    public int questCount() {
        return quests().size();
    }

    /** Distinct chapters. The table has one entry per name, so counting it would count aliases too. */
    public int chapterCount() {
        return chaptersByIdentifier.values().stream().map(ChapterEntry::chapter).distinct().toList().size();
    }

    public int groupCount() {
        return groupsByIdentifier.values().stream().map(GroupEntry::group).distinct().toList().size();
    }

    public boolean isEmpty() {
        return questCount() == 0;
    }
}
