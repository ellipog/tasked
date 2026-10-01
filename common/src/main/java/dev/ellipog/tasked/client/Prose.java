package dev.ellipog.tasked.client;

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
     * The paragraphs with any blank ones at the ends removed. Blank means whitespace-only: a paragraph of
     * spaces draws as an empty line too ({@code TextWrap} treats it as blank), so it is not content at an
     * end either.
     *
     * <p>All-blank (or empty) input comes back empty, which is the caller's own "no description" state --
     * a description of nothing but blank lines says nothing.
     */
    public static List<String> trimmed(List<String> paragraphs) {
        int from = 0;
        int to = paragraphs.size();
        while (from < to && paragraphs.get(from).isBlank()) {
            from++;
        }
        while (to > from && paragraphs.get(to - 1).isBlank()) {
            to--;
        }
        // Nothing to trim is the same list back, not a copy of it: the editor asks this every frame, and
        // the common answer is "nothing to do".
        if (from == 0 && to == paragraphs.size()) {
            return paragraphs;
        }
        return List.copyOf(paragraphs.subList(from, to));
    }
}
