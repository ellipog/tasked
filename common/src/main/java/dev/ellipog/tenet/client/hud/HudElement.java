package dev.ellipog.tenet.client.hud;

/**
 * One thing a player can place, drawn by whichever surface it belongs to.
 *
 * <h2>Why a table rather than a setting per thing</h2>
 *
 * <p>Because there are four of them and there will be more. The book's button appears over the player's
 * inventory; the pinned quests and the notices sit on the HUD. All of them want the same three answers -- is
 * it drawn, where is it, what does it default to -- and all of them want the same editor to change them.
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
 * <h2>What a box here means, now that two of the four are not boxes</h2>
 *
 * <p>{@link #width()} and {@link #height()} are a <b>control's</b> real box and, for a drawn element, only
 * the size the editor falls back to before it has asked for the content's own. A pin list and a notice stack
 * are as tall as what they hold, so their live size comes from {@code HudOverlay}'s own measurement -- one
 * per frame, clamped against the window like every other position here. The distinction is {@link Kind},
 * and it is why the editor's preview no longer asks "is this the button" but "is this a control or drawn".
 *
 * <h2>Where the drawing lives, and why not here</h2>
 *
 * <p>A control draws itself -- the button is an {@code ArmatureButton} on somebody else's screen and knows
 * what it looks like -- while the two HUD elements are drawn by the HUD, which needs a renderer and a window
 * this enum has none of. So the drawing is {@code HudOverlay}'s, as one exhaustive switch per question (what
 * size, and paint), and a fifth element fails to compile until both answer for it. That is the shape this
 * table wanted all along: the note here used to say a drawing hook would wait for a second real caller, and
 * the HUD's own two are it.
 */
public enum HudElement {

    /** The quest book's button, in the window's top-left corner. Drawn by the inventory screen. */
    INVENTORY_BUTTON("inventory_button", 2, 2, 16, 16, 2, true, Kind.CONTROL),

    /**
     * The pinned quests, on the HUD.
     *
     * <p>On by default and harmless while it is: nothing pinned draws nothing, so a player who never pins a
     * quest never sees it. The box below is the editor's starting size; the real one follows what is pinned,
     * so it is as small as what it holds.
     */
    PINNED_QUESTS("pinned_quests", 4, 4, 150, 34, 0, true, Kind.HUD),

    /**
     * The mod's own notices, on the HUD: a task done, a quest done, a chapter done.
     *
     * <p>On by default, and that is the one default here with a consequence worth stating: while it is on, a
     * completion reaches a player as a row where they put this element rather than as a vanilla toast. A
     * player who preferred the toast turns the switch off in the HUD editor and gets it back -- see
     * {@code QuestNotifier} for the routing, which is the only place the two are chosen between.
     *
     * <p>Its default y clears {@link #PINNED_QUESTS}'s default box, so a player who pins something and
     * completes something on their first session does not find the two drawn over each other.
     */
    NOTIFICATIONS("notifications", 4, 44, 150, 14, 0, true, Kind.HUD);

    /** How an element is drawn, which is the one question the editor has to ask about it. */
    public enum Kind {

        /**
         * A control on somebody else's screen: the element's box is real, and the thing draws itself.
         *
         * <p>Its size never changes, so its box is the whole answer and the editor holds the real widget.
         */
        CONTROL,

        /**
         * Drawn by the HUD, and as big as its content.
         *
         * <p>The box in this table is the starting size; {@code HudOverlay} measures the live one every
         * frame, and the editor's preview is resized from that so what is grabbed is what is drawn.
         */
        HUD
    }

    private final String id;
    private final int defaultX;
    private final int defaultY;
    private final int width;
    private final int height;
    private final int iconInset;
    private final boolean defaultOn;
    private final Kind kind;

    HudElement(String id, int defaultX, int defaultY, int width, int height, int iconInset, boolean defaultOn,
               Kind kind) {
        this.id = id;
        this.defaultX = defaultX;
        this.defaultY = defaultY;
        this.width = width;
        this.height = height;
        this.iconInset = iconInset;
        this.defaultOn = defaultOn;
        this.kind = kind;
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

    /**
     * The box the element occupies: a control's real one, and a drawn element's starting one.
     *
     * <p>Which of the two this is depends on {@link #kind()}; see the class note.
     */
    public int width() {
        return width;
    }

    /** The same, vertically. */
    public int height() {
        return height;
    }

    /** How this element is drawn, and so how its size is decided. */
    public Kind kind() {
        return kind;
    }

    /**
     * How far this element's sprite sits inside its box, on every side.
     *
     * <h2>Why the box and its sprite's inset are one entry</h2>
     *
     * <p>Because the editor's preview and the real control both fill the box the table gives them, and a
     * sprite drawn at one inset in the editor and another in the inventory is the fault this table exists
     * to prevent -- two descriptions of one appearance. It is also what decides how much of a small
     * element the sprite fills: a 16-pixel button with an inset of two draws a 12-pixel sprite, which is
     * the size a 16-pixel slot's item wants.
     *
     * <p>Zero for a drawn element, and not because it has no sprite: it has no <i>box to inset a sprite
     * in</i>. Its own drawing decides where its icon goes, so a number here would be a second opinion about
     * an appearance nothing reads it for.
     */
    public int iconInset() {
        return iconInset;
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
     * that has drifted from the language file is a row that reads as {@code tenet.hud.inventory_button}.
     *
     * <p><b>Derived by concatenation, which is why {@code tenet.hud.} is not a swept namespace.</b>
     * {@code LangSweepTest} reads a key it can see whole, and this one is a prefix plus an id: sweeping the
     * namespace would report every one of these labels as an orphan. The keys' existence is pinned by
     * {@code HudElementTest} instead, which is the check that sweep cannot make here.
     */
    public String labelKey() {
        return "tenet.hud." + id;
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
