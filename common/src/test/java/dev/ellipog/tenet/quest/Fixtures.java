package dev.ellipog.tenet.quest;

import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import dev.ellipog.armature.api.data.JsonDocument;
import dev.ellipog.armature.api.data.JsonParseException;
import dev.ellipog.armature.api.data.Problems;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Builds quest files and indexes in memory, for tests.
 *
 * <h2>Why a builder rather than text blocks everywhere</h2>
 *
 * <p>A quest's JSON is mostly optional fields, and a test that only cares about {@code dependsOn}
 * should not have to spell out a title, a position and a task. {@link #q} returns a builder whose
 * defaults are the minimum a quest needs to decode, so a test reads as the one thing it is about:
 *
 * <pre>{@code
 * q("b").dependsOn("a").prerequisiteMode("one_completed").build()
 * }</pre>
 *
 * <h2>This goes through the real codec</h2>
 *
 * <p>{@link #indexOf} parses, then decodes with {@link QuestFile#CODEC}, then indexes — the same
 * three steps the loader takes. So a test that builds an index is also exercising the codecs, and
 * a field that stops decoding breaks the tests rather than passing silently.
 *
 * <p>It deliberately does <b>not</b> validate. Validation needs vanilla's item registry
 * bootstrapped, which is slow and unnecessary for engine tests; {@code QuestValidatorTest} does
 * that separately.
 */
public final class Fixtures {

    private Fixtures() {
    }

    // ------------------------------------------------------------------
    // Parsing and indexing
    // ------------------------------------------------------------------

    public static JsonDocument document(String name, String json) {
        try {
            return JsonDocument.parse(name, json);
        }
        catch (JsonParseException e) {
            throw new IllegalArgumentException("the fixture is not valid JSON: " + e.getMessage(), e);
        }
    }

    public static QuestFile decode(String name, JsonDocument document) {
        DataResult<QuestFile> result = QuestFile.CODEC.parse(JsonOps.INSTANCE, document.root());
        return result.result().orElseThrow(() -> new IllegalArgumentException(
                name + " did not decode: " + result.error().map(DataResult.Error::message).orElse("no message")));
    }

    /**
     * Indexes one or more files, as if they had been loaded from disk.
     *
     * <p>Cross-file dependencies resolve, because the index sees them all — which is what makes a
     * cross-file cycle testable.
     */
    public static QuestIndex indexOf(String... jsons) {
        List<LoadedQuestFile> loaded = new ArrayList<>();
        for (int i = 0; i < jsons.length; i++) {
            String name = "test" + i + ".json";
            JsonDocument document = document(name, jsons[i]);
            loaded.add(new LoadedQuestFile(Path.of(name), name, document, decode(name, document)));
        }
        return QuestIndex.build(loaded, new Problems());
    }

    public static Quest quest(QuestIndex index, String id) {
        return index.quest(id)
                .orElseThrow(() -> new IllegalArgumentException("no quest '" + id + "' in the fixture"))
                .quest();
    }

    // ------------------------------------------------------------------
    // JSON builders
    // ------------------------------------------------------------------

    /** A file with one chapter group and one chapter, holding {@code quests}. */
    public static String file(String... quests) {
        return fileWithChapter("", quests);
    }

    /**
     * A file whose chapter carries extra fields.
     *
     * @param chapterExtras raw JSON for the chapter object, comma-terminated —
     *                      {@code "\"progressionMode\": \"linear\","} for instance
     */
    public static String fileWithChapter(String chapterExtras, String... quests) {
        return """
                {
                  "version": 1,
                  "chapterGroups": [
                    {
                      "id": "group",
                      "title": "Group",
                      "chapters": [
                        {
                          "id": "chapter",
                          "title": "Chapter",
                          %s
                          "quests": [ %s ]
                        }
                      ]
                    }
                  ]
                }
                """.formatted(chapterExtras, String.join(", ", quests));
    }

    /** One chapter object, for a file that needs more than one. */
    public static String chapter(String id, String... quests) {
        return chapterWith(id, "", quests);
    }

    /**
     * One chapter object carrying extra fields.
     *
     * <p>The chapter-level counterpart of {@link #fileWithChapter}, and it exists for the same reason:
     * a test about a chapter's own gate should read as the gate and nothing else. Spliced raw rather
     * than modelled, so a test can also write a field the codec does not know — which is how a
     * validator's unknown-field message is exercised.
     *
     * @param extras raw JSON for the chapter object, comma-terminated —
     *               {@code "\"dependsOn\": [\"first\"],"} for instance
     */
    public static String chapterWith(String id, String extras, String... quests) {
        return "{\"id\": \"" + id + "\", \"title\": \"" + id + "\", " + extras + "\"quests\": [ "
                + String.join(", ", quests) + " ] }";
    }

    /**
     * A file whose group and chapter carry the ids given, rather than {@link #file}'s fixed pair.
     *
     * <p>Needed by anything that indexes <b>two</b> files. {@code file} always calls its group {@code group}
     * and its chapter {@code chapter}, so two of them in one index declare two groups under one id and two
     * chapters under one id — genuine duplicates, which {@code QuestIndex} now reports and <i>drops</i>, and
     * a dropped group takes its whole subtree with it. A test that means "two files" therefore has to say
     * which two, or its second file silently vanishes from the fixture.
     */
    public static String fileAs(String groupId, String chapterId, String... quests) {
        return """
                {
                  "version": 1,
                  "chapterGroups": [
                    {
                      "id": "%s",
                      "title": "%s",
                      "chapters": [
                        {
                          "id": "%s",
                          "title": "%s",
                          "quests": [ %s ]
                        }
                      ]
                    }
                  ]
                }
                """.formatted(groupId, groupId, chapterId, chapterId, String.join(", ", quests));
    }

    /**
     * A file with as many chapters as are given, each built by {@link #chapter}.
     *
     * <p>One group holding them all, which is the shape a real file has. A test that needs a dependency
     * to cross a chapter boundary needs two chapters in one index, and that is the only way to get one.
     */
    public static String fileWithChapters(String... chapters) {
        return "{\"version\": 1, \"chapterGroups\": [ { \"id\": \"group\", \"title\": \"Group\", "
                + "\"chapters\": [ " + String.join(", ", chapters) + " ] } ] }";
    }

    public static Builder q(String id) {
        return new Builder(id);
    }

    /**
     * A quest, with only the fields a test asks for.
     *
     * <p>Defaults: one required checkmark task, no dependencies, no flags. A quest with nothing at
     * all would decode and be completable immediately, which makes a poor starting point.
     */
    public static final class Builder {

        private final String id;
        private final List<String> dependsOn = new ArrayList<>();
        private final List<String> aliases = new ArrayList<>();
        private final List<Integer> optionalTasks = new ArrayList<>();

        private String prerequisiteMode;
        private Integer minRequired;
        private String exclusiveGroup;
        private Boolean sequentialTasks;
        private Boolean repeatable;
        private Integer repeatCooldownTicks;
        private Boolean invisible;
        private Boolean showTitle;
        private Integer maxCompletableDependents;
        private Integer invisibleUntilTasks;
        private Boolean hideUntilDependenciesComplete;
        private Boolean hideUntilDependenciesVisible;
        private Boolean hideDependencyLines;
        private Boolean hideTextUntilComplete;
        private Boolean hideDetailsUntilStartable;
        private Double iconScale;
        private int taskCount = 1;
        private int x;
        private int y;
        private int drawnSize = 48;

        private Builder(String id) {
            this.id = id;
        }

        /**
         * Where the quest sits on the canvas, in the file's own pixel units.
         *
         * <p>Needed because two of {@link QuestIndex}'s checks are about geometry rather than about
         * references: two quests at the same coordinates, and two quests close enough together that
         * their titles cannot both be drawn. Neither is testable without saying where a quest is.
         */
        public Builder at(int x, int y) {
            this.x = x;
            this.y = y;
            return this;
        }

        /**
         * The node's drawn size, which mirrors {@link QuestLayout}'s own field.
         *
         * <p>Present because this builder's job is to be able to spell out any field a quest can have,
         * so a test never has to drop to raw JSON to set one. Nothing uses it yet — the crowding check
         * measures a title's width, not a node's — and it is here so that the fixture stays a mirror of
         * the format rather than a subset of it that grows one field at a time.
         */
        public Builder drawnSize(int size) {
            this.drawnSize = size;
            return this;
        }

        /**
         * Whether the book draws this quest's name under its node.
         *
         * <p>Needed by the crowding tests, which turn on it: the check compares the space between two
         * nodes against the width of the titles that would be drawn there, so two titles that are
         * <b>not being drawn</b> cannot crowd anything. See {@code checkCrowdedRows}.
         */
        public Builder showTitle(boolean value) {
            this.showTitle = value;
            return this;
        }

        /** The share of the node the icon fills. Out-of-range values are the codec's problem, not the builder's. */
        public Builder iconScale(double value) {
            this.iconScale = value;
            return this;
        }

        public Builder dependsOn(String... ids) {
            dependsOn.addAll(List.of(ids));
            return this;
        }

        /** A former id. Renaming a quest should never orphan progress or break a reference. */
        public Builder alias(String... ids) {
            aliases.addAll(List.of(ids));
            return this;
        }

        /** One of {@code all_completed}, {@code one_completed}, {@code all_started}, {@code one_started}. */
        public Builder prerequisiteMode(String mode) {
            this.prerequisiteMode = mode;
            return this;
        }

        /** How many dependencies must be satisfied. Overrides the mode when set. */
        public Builder minRequired(int count) {
            this.minRequired = count;
            return this;
        }

        public Builder exclusiveGroup(String group) {
            this.exclusiveGroup = group;
            return this;
        }

        public Builder sequentialTasks(boolean value) {
            this.sequentialTasks = value;
            return this;
        }

        public Builder repeatable(boolean value) {
            this.repeatable = value;
            return this;
        }

        public Builder repeatCooldownTicks(int ticks) {
            this.repeatCooldownTicks = ticks;
            return this;
        }

        public Builder invisible(boolean value) {
            this.invisible = value;
            return this;
        }

        /** At most this many of the quests that depend on this one may be completed. */
        public Builder maxCompletableDependents(int count) {
            this.maxCompletableDependents = count;
            return this;
        }

        /** With {@link #invisible(boolean)}, the number of tasks that make the quest appear. */
        public Builder invisibleUntilTasks(int count) {
            this.invisibleUntilTasks = count;
            return this;
        }

        public Builder hideUntilDependenciesComplete(boolean value) {
            this.hideUntilDependenciesComplete = value;
            return this;
        }

        public Builder hideUntilDependenciesVisible(boolean value) {
            this.hideUntilDependenciesVisible = value;
            return this;
        }

        public Builder hideDependencyLines(boolean value) {
            this.hideDependencyLines = value;
            return this;
        }

        public Builder hideTextUntilComplete(boolean value) {
            this.hideTextUntilComplete = value;
            return this;
        }

        public Builder hideDetailsUntilStartable(boolean value) {
            this.hideDetailsUntilStartable = value;
            return this;
        }

        /** How many checkmark tasks. Default one. */
        public Builder tasks(int count) {
            this.taskCount = count;
            return this;
        }

        /** No tasks at all, so the quest is completable by declaration. */
        public Builder noTasks() {
            this.taskCount = 0;
            return this;
        }

        /** Marks a task optional. Every task that is not marked is required. */
        public Builder optional(int index) {
            optionalTasks.add(index);
            return this;
        }

        public String build() {
            StringBuilder json = new StringBuilder("{\"id\": \"")
                    .append(id).append("\", \"title\": \"").append(id).append('"');

            // Emitted only when they differ from the codec's defaults, so a test that does not care
            // about layout reads exactly as it did before layout support existed.
            if (x != 0 || y != 0) {
                json.append(", \"x\": ").append(x).append(", \"y\": ").append(y);
            }
            if (drawnSize != 48) {
                json.append(", \"size\": ").append(drawnSize);
            }

            if (!aliases.isEmpty()) {
                json.append(", \"aliases\": ").append(strings(aliases));
            }
            if (!dependsOn.isEmpty()) {
                json.append(", \"dependsOn\": ").append(strings(dependsOn));
            }
            if (prerequisiteMode != null) {
                json.append(", \"prerequisiteMode\": \"").append(prerequisiteMode).append('"');
            }
            if (minRequired != null) {
                json.append(", \"minRequired\": ").append(minRequired);
            }
            if (exclusiveGroup != null) {
                json.append(", \"exclusiveGroup\": \"").append(exclusiveGroup).append('"');
            }
            if (maxCompletableDependents != null) {
                json.append(", \"maxCompletableDependents\": ").append(maxCompletableDependents);
            }
            if (invisibleUntilTasks != null) {
                json.append(", \"invisibleUntilTasks\": ").append(invisibleUntilTasks);
            }
            if (hideUntilDependenciesComplete != null) {
                json.append(", \"hideUntilDependenciesComplete\": ").append(hideUntilDependenciesComplete);
            }
            if (hideUntilDependenciesVisible != null) {
                json.append(", \"hideUntilDependenciesVisible\": ").append(hideUntilDependenciesVisible);
            }
            if (hideDependencyLines != null) {
                json.append(", \"hideDependencyLines\": ").append(hideDependencyLines);
            }
            if (hideTextUntilComplete != null) {
                json.append(", \"hideTextUntilComplete\": ").append(hideTextUntilComplete);
            }
            if (hideDetailsUntilStartable != null) {
                json.append(", \"hideDetailsUntilStartable\": ").append(hideDetailsUntilStartable);
            }
            if (sequentialTasks != null) {
                json.append(", \"sequentialTasks\": ").append(sequentialTasks);
            }
            if (repeatable != null) {
                json.append(", \"repeatable\": ").append(repeatable);
            }
            if (repeatCooldownTicks != null) {
                json.append(", \"repeatCooldownTicks\": ").append(repeatCooldownTicks);
            }
            if (invisible != null) {
                json.append(", \"invisible\": ").append(invisible);
            }
            if (showTitle != null) {
                json.append(", \"showTitle\": ").append(showTitle);
            }
            if (iconScale != null) {
                json.append(", \"iconScale\": ").append(iconScale);
            }
            if (taskCount > 0) {
                json.append(", \"tasks\": [");
                for (int i = 0; i < taskCount; i++) {
                    if (i > 0) {
                        json.append(", ");
                    }
                    json.append("{\"type\": \"tenet:checkmark\", \"title\": \"task ").append(i).append('"');
                    if (optionalTasks.contains(i)) {
                        json.append(", \"optional\": true");
                    }
                    json.append('}');
                }
                json.append(']');
            }
            return json.append('}').toString();
        }

        private static String strings(List<String> values) {
            return values.stream().map(v -> "\"" + v + "\"").collect(Collectors.joining(", ", "[", "]"));
        }
    }
}
