package dev.ellipog.tenet.quest;

import dev.ellipog.armature.api.data.JsonDocument;

import java.util.ArrayList;
import java.util.List;

/**
 * A whole questline, as pieces that each remember which document they were written in.
 *
 * <h2>Why the tree is flat and every piece carries its own source</h2>
 *
 * <p>Because version 2 spreads one logical tree across <b>three documents</b>. A group is in
 * {@code group.json}, each of its chapters is in a {@code chapter.json} in a folder of its own, and
 * each quest is in a file of its own — so "the file a chapter came from" is a property of the chapter
 * and not of the tree, the group, or the load.
 *
 * <p>That is the whole reason this type exists. {@link QuestIndex} used to hold a list of
 * {@link LoadedQuestFile} and compute every position from it: a quest's error location was
 * {@code $.chapterGroups[g].chapters[c].quests[q]}, evaluated against <i>one</i> document, which is
 * exactly right for version 1 — where a file <b>is</b> a tree — and meaningless for version 2, where
 * that path does not exist in any file that was read. The result would not be a crash. It would be an
 * error message naming the wrong file and a line from the wrong document, which is the one failure this
 * project's whole validator arrangement exists to prevent: a message whose position sends the reader to
 * a place the mistake is not.
 *
 * <p>So positions are decided by the step that read the file, and travel with the piece. Nothing
 * downstream computes a path, and there is therefore nothing that can compute one wrongly.
 *
 * <h2>Order</h2>
 *
 * <p>Pieces are in <b>declaration order</b>, and that is the only order the index needs: within each
 * kind, the order the pieces appear is the order that kind's list comes out in. Groups are therefore in
 * the order {@link QuestFiles} found their folders, a group's chapters in the order its manifest lists
 * them, and a chapter's quests in the order its manifest lists them — which for a {@code LINEAR}
 * chapter <i>is</i> the progression, so nothing may reorder this list.
 *
 * <p>{@link #of(List)} builds one from version-1 files, and is the only place the version-1 paths are
 * written out. Keeping it here rather than in {@link QuestIndex} means the index has no opinion about
 * either layout.
 *
 * @param pieces every group, chapter and quest, each with the document it came from
 */
public record QuestTree(List<Piece> pieces) {

    /** A tree with nothing in it. */
    public static final QuestTree EMPTY = new QuestTree(List.of());

    /**
     * Where a piece of the tree was written.
     *
     * <p>Three fields and no more, because they are exactly what a message needs: the file to name, the
     * document to ask for a line and column, and the path within it. {@code path} is {@code $} for a
     * version-2 per-kind file — the file <i>is</i> the declaration — and the nested version-1 path
     * inside a flat file. Both are settled by whoever read the file, which is the only place that knows.
     */
    public record Source(String file, JsonDocument document, String path) {

        /** Where this is, for a message: {@code getting_started/group.json:4:9}. */
        public String location() {
            return file + ":" + document.nearestLocation(path);
        }

        /** The same source with a field appended to the path, for a problem about a field within it. */
        public Source field(String name) {
            return new Source(file, document, path + "." + name);
        }
    }

    /**
     * One piece of the tree. Sealed, so a walk over it is exhaustive and a fourth kind cannot be added
     * without every walk failing to compile.
     *
     * <h2>Why these are called {@code GroupPiece} and not {@code Group}</h2>
     *
     * <p>Because a nested type <b>shadows a top-level type of the same simple name</b> everywhere inside
     * its enclosing class — so a record called {@code Chapter} nested here would capture the simple name
     * {@code Chapter} for the whole of {@link QuestTree}, and every reference to the real
     * {@link Chapter} in this file would silently mean the piece instead. The compiler catches it, as a
     * paragraph of type errors rather than as a shadowed name; naming the pieces distinctly means there
     * is nothing to catch.
     */
    public sealed interface Piece {

        /** Where this piece was written. */
        Source source();

        /**
         * A chapter group, with its chapters already assembled.
         *
         * <p>Assembled rather than named, because by the time a tree exists the folder names have been
         * resolved: what a manifest <i>claimed</i> and what the loader <i>found</i> are different
         * questions, and only the second belongs in a loaded tree. The first is {@link GroupManifest}'s,
         * and it is what makes "the manifest lists a chapter that is not there" reportable at all.
         */
        record GroupPiece(ChapterGroup group, Source source) implements Piece {
        }

        /**
         * A chapter, with its quests already assembled.
         *
         * <p>{@code groupId} is carried because a chapter has no back-pointer to its group — the nesting
         * in version 1 supplied one for free, and a flat piece list does not.
         */
        record ChapterPiece(String groupId, Chapter chapter, Source source) implements Piece {
        }

        /**
         * One quest, with the chapter it sits in and its position in that chapter's list.
         *
         * <p>{@code chapter} is the very object whose {@code quests()} list {@code orderInChapter}
         * indexes, and {@code orderInChapter} was taken while that list was built. Carrying both makes
         * "the position agrees with the list" true by construction rather than by two places counting
         * the same things — which matters because a {@code LINEAR} chapter gates on that position, and a
         * version that is wrong in the permissive direction unlocks quests with no error at all.
         */
        record QuestPiece(String groupId, Chapter chapter, Quest quest, int orderInChapter, Source source)
                implements Piece {
        }
    }

    /**
     * The version-1 adapter: one file, one whole tree, every path inside the same document.
     *
     * <p>This is the only code that knows what a version-1 path looks like, and it is here so that
     * neither {@link QuestIndex} nor the loader has to. It stays forever, because version-1 files are
     * read forever — which is why it is written as an adapter rather than as a migration.
     */
    public static QuestTree of(List<LoadedQuestFile> files) {
        List<Piece> pieces = new ArrayList<>();

        for (LoadedQuestFile loaded : files) {
            JsonDocument document = loaded.document();

            for (int g = 0; g < loaded.file().chapterGroups().size(); g++) {
                ChapterGroup group = loaded.file().chapterGroups().get(g);
                String groupPath = QuestValidator.groupPath(g);
                pieces.add(new Piece.GroupPiece(group, new Source(loaded.displayName(), document, groupPath)));

                for (int c = 0; c < group.chapters().size(); c++) {
                    Chapter chapter = group.chapters().get(c);
                    String chapterPath = QuestValidator.chapterPath(g, c);
                    pieces.add(new Piece.ChapterPiece(group.id(), chapter,
                            new Source(loaded.displayName(), document, chapterPath)));

                    for (int q = 0; q < chapter.quests().size(); q++) {
                        pieces.add(new Piece.QuestPiece(group.id(), chapter, chapter.quests().get(q), q,
                                new Source(loaded.displayName(), document,
                                        QuestValidator.questPath(g, c, q))));
                    }
                }
            }
        }

        return new QuestTree(List.copyOf(pieces));
    }

    /** How many pieces there are, of every kind. */
    public int size() {
        return pieces.size();
    }

    /** Whether there is nothing at all. */
    public boolean isEmpty() {
        return pieces.isEmpty();
    }
}
