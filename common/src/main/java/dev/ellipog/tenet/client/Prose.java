package dev.ellipog.tenet.client;

import java.util.List;

/**
 * The prose rule: <b>a blank paragraph at either end of a description is not content</b>.
 *
 * <h2>Why this exists</h2>
 *
 * <p>A description is a list of paragraphs, and an empty string is a blank line the author wrote -- that
 * is what the format says and what the layout reserves room for. At the <i>ends</i>, though, a blank
 * paragraph is almost never written on purpose: it is where the caret was left after pressing Enter at
 * the end of the text, and it is invisible in the file. It was found in play as *"loads of empty space at
 * the bottom for some reason"* -- the description it was reported against ended in two empty paragraphs,
 * which the editor and the reader then reserved two whole lines for, below the text.
 *
 * <p>So the ends are not content and the middle is: a blank paragraph between two paragraphs is a
 * paragraph break and is kept, and everything between the first and last non-blank paragraph is kept
 * exactly as written -- including blank runs. Trimming only what nothing can be seen to mean is the rule;
 * anything more would be the editor editing prose.
 *
 * <h2>Three readers of the format, one rule</h2>
 *
 * <p>The description is read at three places: the synced tree's parse ({@code ClientQuestCache}, which is
 * what a reader draws), the replica JSON ({@code QuestBookScreen.shownDescription}, which is what the
 * editor draws and edits), and the commit's own split of the edited text back into paragraphs. All three
 * call this, because three copies of "drop the blank ends" is how one of them ends up keeping them and
 * the card drawing its prose shorter than the file's.
 */
public final class Prose {

    private Prose() {
    }

    /**
     * One string as paragraphs: split on newlines, with a trailing empty line kept.
     *
     * <h2>Why {@code -1} and why this is here rather than inline</h2>
     *
     * <p>{@code -1} keeps a trailing empty paragraph, which is the caret's own line while somebody is
     * typing and is dropped by {@link #trimmed} at the ends either way — but it is kept in the middle
     * of the conversion, so a text ending in two newlines is three paragraphs and not two. The default
     * split would silently swallow them.
     *
     * <p>It is a method because two roads produce paragraphs from one string — the editor's own field,
     * and a {@code lang} file's whole-description override — and they have to agree. A translator who
     * writes a description with a blank line in it means the same thing the author typing one means,
     * and two spellings of "split on newline" is how one of them ends up one paragraph short.
     */
    public static List<String> split(String text) {
        return List.of(text.split("\n", -1));
    }

    /**
     * The paragraphs with any blank ones at the ends removed. Blank means whitespace-only: a paragraph of
     * spaces draws as an empty line too ({@code TextWrap} treats it as blank), so it is not content at an
     * end either.
     *
     * <p>All-blank (or empty) input comes back empty, which is the caller's own "no description" state --
     * a description of nothing but blank lines says nothing.
     */
    public static List<String> trimmed(List<String> paragraphs) {
        return ends(paragraphs).cut(paragraphs);
    }

    /**
     * Where the content sits in a paragraph list: the first and last non-blank paragraph.
     *
     * <h2>Why the bounds are a value rather than a loop in one method</h2>
     *
     * <p>Because a description can arrive as <b>two</b> parallel lists — the paragraphs, and the English
     * words for the ones that are translation keys — and the second has to be cut to the same ends as
     * the first. Trimming it by its own blankness would be wrong in a way that is hard to see: a literal
     * paragraph in the middle carries an <i>empty</i> fallback, so a rule that dropped blanks would drop
     * that entry and shift every paragraph after it onto the wrong translation. So the bounds are
     * computed once, from the prose, and applied to both lists.
     */
    public record Ends(int from, int to) {

        /**
         * The part of {@code paragraphs} these ends describe.
         *
         * <p>Clamped to the list's own size, because the parallel list may be shorter than the prose —
         * a server that sent fewer fallbacks than paragraphs leaves the tail reading as literal, which
         * is what those paragraphs are.
         */
        public List<String> cut(List<String> paragraphs) {
            int last = Math.min(to, paragraphs.size());
            int first = Math.min(from, last);
            // Nothing to trim is the same list back, not a copy of it: the editor asks this every frame,
            // and the common answer is "nothing to do".
            if (first == 0 && last == paragraphs.size()) {
                return paragraphs;
            }
            return List.copyOf(paragraphs.subList(first, last));
        }
    }

    /** The ends of the prose in a paragraph list. See {@link Ends}. */
    public static Ends ends(List<String> paragraphs) {
        int from = 0;
        int to = paragraphs.size();
        while (from < to && paragraphs.get(from).isBlank()) {
            from++;
        }
        while (to > from && paragraphs.get(to - 1).isBlank()) {
            to--;
        }
        return new Ends(from, to);
    }
}
