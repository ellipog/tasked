package dev.ellipog.tasked.client.dev;

import dev.ellipog.armature.client.ArmatureTheme;

/**
 * What the quest editor's inline fields draw with, so that opening one changes nothing about the picture.
 *
 * <h2>The report this is the answer to</h2>
 *
 * <p>From play: *"make the description box and all text fields in general look exactly the same when I
 * edit and when I click out of it"*. Every difference between a value being edited and the same value at
 * rest was a choice someone made, and these are the choices now: the field is drawn in the ink the
 * reader draws the same value in, and the widget paints no box at all. What is left when a field is
 * focused is the caret and the selection -- what an editor needs and a reader does not have.
 *
 * <h2>Why this is a class and not two colours at the call site</h2>
 *
 * <p>Because the reader and the editor have to agree, and the reader is not the only caller: the title
 * and the subtitle lines are drawn by the reader's own path with {@link #ink}, so a change to one is a
 * change to both. A colour that only the editor knew about is the fault this exists to prevent -- the
 * body of the card used to be drawn in {@code title()} the moment it was clicked, which is precisely
 * what "it does not look the same" was.
 */
public final class InlineEdit {

    /**
     * The colour a widget draws when it must not be seen: fully transparent, so what stands behind it --
     * the card, the header strip, a hovered row -- is what the reader sees, exactly as before the click.
     *
     * <p>Passed for both the fill and the edge. The widget's default pair is a visible box, which is
     * right for a form field ({@code drawSettings} and the theme editor's hex field keep theirs) and
     * wrong for a field standing in for text.
     */
    public static final int NO_BOX = 0x00000000;

    /** The path the requires section's "+ Add by id" field is opened at, named once for both ends. */
    public static final String DEPENDENCY_ADD = "dep:add";

    private InlineEdit() {
    }

    /**
     * Whether the open editor is standing in for the piece at {@code path}: <b>one drawing of a value,
     * never two</b>.
     *
     * <h2>The fault this is the answer to</h2>
     *
     * <p>The editor draws the value it is editing -- that is the point of being able to edit it in place
     * -- and the edit path draws that same value on the card. Both were drawn at once, and because the
     * field's box is transparent (so that the text does not move) neither hid the other: the description
     * came out printed twice, a line apart. The box being opaque is what used to hide the second copy,
     * which is why the fault only appeared once the box was made invisible.
     *
     * <p>So the callers of this method are the drawing sites of everything a field can replace -- the
     * description's prose, a row part, the title, the subtitle, the raw-JSON label, the dependency add
     * row -- and each draws its piece only when no field is standing in for it. A site that forgets is a
     * value drawn twice, which is what the editor's own report is about; a test cannot see the pixels,
     * so the method is small, named and used at every one of them.
     */
    public static boolean replaces(String path, String editingPath) {
        return path != null && path.equals(editingPath);
    }

    /**
     * The ink an edited value is drawn in, by the path the piece is edited at: the ink the reader draws
     * that value in.
     *
     * <p>Three answers, because the reader has three. {@code drawOverlay} draws the quest's title in
     * {@link ArmatureTheme#title()} and the chapter/subtitle line in {@link ArmatureTheme#faint()}, and
     * both call this method, so the reading ink and the editing ink cannot drift. Everything else --
     * the description's prose and every task, reward and dependency value -- is
     * {@link ArmatureTheme#body()}, which is what the reader's prose and row draws use.
     */
    public static int ink(String path) {
        if ("title".equals(path)) {
            return ArmatureTheme.title();
        }
        if ("subtitle".equals(path)) {
            return ArmatureTheme.faint();
        }
        return ArmatureTheme.body();
    }
}
