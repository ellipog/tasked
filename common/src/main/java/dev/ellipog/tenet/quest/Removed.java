package dev.ellipog.tenet.quest;

/**
 * One recoverable delete: the name it is set aside under, and what restoring it would make.
 *
 * <h2>Why this is a type rather than a list of strings</h2>
 *
 * <p>Because the two facts a listing has to carry are not the same fact: <i>where</i> the copy is, which
 * is what a restore is asked for, and <i>what it was</i>, which is the half a person needs to decide
 * whether to ask. A bare path — {@code getting_started/first_steps.deleted} — says neither: the suffix
 * hides the name it would come back as, and nothing in the string says whether that folder was a chapter
 * or a group.
 *
 * <p>{@link Kind} is deliberately its own vocabulary rather than {@link QuestFiles.Kind}, which answers a
 * different question — what a <i>declaration</i> is, for the loader's assembly. A tombstone is not a
 * declaration; it is a thing on disk that nothing loads.
 *
 * @param path    where the copy is, relative to the quest root and {@code /}-separated: the argument a
 *                restore takes
 * @param kind    what it was
 * @param name    what it would come back as — the file or folder name without the suffix
 * @param chapter the chapter that holds it, for a quest file, and null for everything else. An editor
 *                session is opened on a chapter, so a quest file's restore has to name one, and the folder
 *                the tombstone sits in is that chapter
 */
public record Removed(String path, Kind kind, String name, String chapter) {

    /** What a set-aside thing was, in the words a sentence about it uses. */
    public enum Kind {

        /** A folder of chapters, with a {@code group.json} in it. */
        GROUP("chapter group"),

        /** A folder of quest files, with a {@code chapter.json} in it. */
        CHAPTER("chapter"),

        /** One quest file. */
        QUEST("quest file"),

        /** One reward table. */
        TABLE("reward table");

        private final String word;

        Kind(String word) {
            this.word = word;
        }

        /** The noun for a sentence: "a chapter", "a quest file". */
        public String word() {
            return word;
        }
    }

    /**
     * One line for a listing: what it is, where it is, and the name a restore would give it.
     *
     * <p>The path is first because it is the argument: a person reading this is about to type one of these
     * lines back, so the thing they have to copy is the thing the line starts with.
     */
    public String sentence() {
        return path + "  (" + kind.word() + ", restores as " + name + ")";
    }
}
