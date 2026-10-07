package dev.ellipog.tasked.client.hud;

/**
 * One thing a player can place, drawn by whichever surface it belongs to.
 *
 * <h2>Why a table rather than a setting per thing</h2>
 *
 * <p>Because there are already two of them and there will be more. The book's button appears over the
 * player's inventory; a pinned quests panel is coming and sits on the HUD. Both want the same three answers
 * -- is it drawn, where is it, what does it default to -- and both want the same editor to change them.
 * Written per thing, that is a class each and the editor learns about each one; written as a table, the
 * editor walks {@link #values()} and a new element is one line here plus its own drawing.
 *
 * <h2>A position, in the window's own pixels</h2>
 *
 * <p>{@link #defaultX()} and {@link #defaultY()} are GUI pixels from the window's top-left corner, and that
 * is the same corner the HUD is measured from -- so what the editor shows is what the game draws, with no
 * conversion in between and nothing to get wrong at a second window size.
 *
 * <p><b>This replaced an offset from the container panel's corner, and the reason is worth keeping.</b> The
 * panel-anchored version had the book's button travel with the inventory panel when the recipe book slid it
 * across the window, which is what that anchor was for. But the editor has no panel to draw against, so it
 * had to invent one: a ghost of the inventory panel, centred in the editor's own canvas -- and two corners
 * that are not the same corner, with a stored number measured from each, is a control that jumps between the
 * editor and the game. The user-visible symptom was exactly that, and a straight position in one space
 * cannot have it.
 *
 * <h2>What is deliberately absent</h2>
 *
 * <p>No drawing. The two elements do not draw the same way and neither of them draws <i>here</i> -- the
 * button is an {@code ArmatureButton} on a screen, and the pinned panel will be drawn by the HUD -- so a
 * drawing hook with one real caller would be a seam in search of a second one. The editor knows how to draw
 * the button because it holds the real control; see {@code HudElementPreview}.
 */
public enum HudElement {

    /** The quest book's button, in the window's top-left corner. */
    INVENTORY_BUTTON("inventory_button", 4, 4, 20, 20, true);

    private final String id;
    private final int defaultX;
    private final int defaultY;
    private final int width;
    private final int height;
    private final boolean defaultOn;

    HudElement(String id, int defaultX, int defaultY, int width, int height, boolean defaultOn) {
        this.id = id;
        this.defaultX = defaultX;
        this.defaultY = defaultY;
        this.width = width;
        this.height = height;
        this.defaultOn = defaultOn;
    }

    /** The key this element is stored under in {@code hud.json}. */
    public String id() {
        return id;
    }

    /** Where it sits before anybody moves it, as a position in the window. */
    public int defaultX() {
        return defaultX;
    }

    /** The same, vertically. */
    public int defaultY() {
        return defaultY;
    }

    /** The box the element occupies, which is also its hit target in the editor. */
    public int width() {
        return width;
    }

    /** The same, vertically. */
    public int height() {
        return height;
    }

    /** Whether it is drawn before anybody switches it off. */
    public boolean defaultOn() {
        return defaultOn;
    }

    /**
     * The row's own name, derived rather than stored.
     *
     * <p>One place decides what an element is called, so a rename here renames the label, the editor's row
     * and the language key together -- and {@code HudSettingsTest} pins the shape, because a derived key
     * that has drifted from the language file is a row that reads as {@code tasked.hud.inventory_button}.
     */
    public String labelKey() {
        return "tasked.hud." + id;
    }

    /**
     * The element a stored key names, or null when it names none this build has.
     *
     * <p>Null rather than a default, because the caller is reading a file: a key written by a newer build, or
     * misspelt by hand, must cost that entry and nothing else. Same rule and same reason as
     * {@code DevMode.kindNamed}.
     */
    public static HudElement named(String id) {
        if (id == null) {
            return null;
        }
        String wanted = id.trim();
        for (HudElement element : values()) {
            // Either spelling: the stored id, which is what the file holds, or the constant's own name,
            // which is what somebody reading this enum would guess. Case does not matter, because this file
            // is hand-edited.
            if (element.id.equalsIgnoreCase(wanted) || element.name().equalsIgnoreCase(wanted)) {
                return element;
            }
        }
        return null;
    }
}
