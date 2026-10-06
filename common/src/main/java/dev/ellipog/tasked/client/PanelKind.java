package dev.ellipog.tasked.client;

/**
 * Which overlay is open: a quest, the item picker, the rewards inbox, and so on.
 *
 * <h2>An identity, not a presentation</h2>
 *
 * <p>This names <b>what</b> is open and says nothing about <b>how</b> it is drawn. Every kind is a centred
 * card today, and a docked side panel is the second presentation this class was promoted for — so the two
 * read the same constant and neither owns it. That is why the name is not {@code Overlay}: the old name
 * described the card, and the card is now one of two shapes the same thing can take.
 *
 * <h2>Why it is a package-level type rather than nested in the screen</h2>
 *
 * <p>Because the rules about which kind may occupy which column are asserted without a client — see
 * {@code PanelStack} and {@code PanelStackTest} — and a nested {@code private} enum cannot be named by a
 * test. It was nested and private while the screen was its only reader, which was true then and is not
 * now. Nothing else about it changed in the move: the constants and their notes are as they were.
 *
 * <p>{@link #NONE} is the <i>absence</i> of an overlay rather than one of them, which is why it is the
 * value every guard is written against and the value a screen with nothing open holds.
 */
public enum PanelKind {

    /** Nothing is open: no card, no panel. The value a screen with nothing showing holds. */
    NONE,

    /** The quest itself — the reader, and the editor in edit mode. What a node click opens. */
    QUEST,

    PARTY,

    /**
     * The item picker on its own, for a field that is not a quest's.
     *
     * <p>It used to be a page of the quest card and nothing else, because every field it served
     * belonged to a quest. The Chapter tab's icon is the first caller outside that card, and a picker
     * that demanded a quest to open would have meant a second picker -- so it became an overlay of
     * its own, drawn with the same card and the same list. See {@code QuestBookScreen#pickTarget}.
     */
    PICKER,

    /**
     * The texture picker: the pack's own PNG files, for the canvas image's Texture row.
     *
     * <p>Its own overlay rather than a page of the picker's, because the two choose different
     * things from different lists: an item is a registry entry with a stack to draw, a texture is a
     * file with a picture to probe, and the item picker's catalogue, "current" row and commit all
     * belong to a field of a quest or a chapter's identity. Sharing the card's shape while sharing
     * none of that state is what keeps each picker's rules testable on its own -- see
     * {@code TexturePicker} for the rules and {@code QuestBookScreen#drawTexturePicker} for the drawing.
     */
    TEXTURE,

    /**
     * A choice reward's entries, waiting for the player's answer.
     *
     * <p>A card of its own because the question has no quest card behind it: the offer arrives when
     * the player claims, which may be from the reader, from the canvas, or from the rewards panel --
     * and it outlives any one of them, held in {@code ClientChoiceOffers} until it is answered or
     * dismissed. Dismissing loses nothing: the reward stays outstanding on the server, so pressing
     * Claim again produces the same question.
     */
    CHOICE,

    /**
     * What the server owes this player, and the one press that collects all of it.
     *
     * <p>A list rather than a pile of buttons on the canvas: a finished quest's rewards are the
     * server's to state (see {@code ClientQuestCache.canClaimFor}), and a card is where a player can
     * read them before spending them. Opened from the header, between Party and Close.
     */
    REWARDS,

    /**
     * The naming card: a title and an id for a chapter or a group being made, renamed or duplicated.
     *
     * <p>A card of its own rather than a page of the quest card, because what it names may have no
     * quest and no chapter behind it at all -- a new group, or a chapter about to be created. It is
     * the one overlay opened from the sidebar rather than from a quest, and the one whose fields are
     * read without an editor session: the ops it sends are structural.
     */
    NAMING,

    /**
     * The player's own settings: the theme list, the corner radius and the Motion switch.
     *
     * <p>A card of its own rather than a page of the tools panel, which is the author's -- the
     * settings it holds are every player's, and they were behind the edit permission until this
     * existed. See {@code SettingsLayout} for the rows and {@code Look} for what they change.
     */
    SETTINGS,

    /**
     * The reward tables, for a reward's table field: a list of them to choose from, with the rows
     * that make and manage one.
     *
     * <p>An overlay rather than a page of the card, because it is a list of the pack's assets rather
     * than a field of the quest being edited — the same argument the item picker's overlay makes.
     */
    TABLE_BROWSER,

    /**
     * One table, open for editing: its entries, their weights, and the chances those weights mean.
     *
     * <p>The panel this whole feature exists for. It edits a named table's file or a table written
     * inline in a reward, through the same controls, and it can walk into a table an entry points at
     * and back out again.
     */
    TABLE_EDITOR,

    /**
     * The pack, listed: its reward tables, its quest files, and the types a file may name.
     *
     * <p>The author's way in without a quest. Every other road to a table runs through a reward's
     * table field, so a table nobody has referenced yet — the one an author is about to write — could
     * not be reached at all from inside the game. This panel is that road, and it is also the only
     * place a table file the loader <i>refused</i> can be seen, because such a file is not a table and
     * appears in no other list.
     */
    ASSETS,

    /**
     * The author's own dock: the Book and Chapter tabs, over the canvas.
     *
     * <h2>Why it is a kind at all, when it is not an overlay</h2>
     *
     * <p>Because it occupies the same column an overlay does, and one column with two owners is how the
     * two came to disagree: this dock and a panel shared column 1 but were positioned, routed and revealed
     * by two different sets of arithmetic, so swapping between them moved the panel's edge. Being a kind is
     * what makes them one object to the rest of the screen — the same rail, the same press routing, the same
     * grip, the same arrival — and it is what lets a child be filed <i>beside</i> the dock.
     *
     * <p><b>It has no card form, and that is a property rather than an omission.</b> A "tools screen" was
     * tried first and rejected: the tool exists to watch the canvas it floats over. So the presentation
     * switch must not be able to turn this into a centred card — see
     * {@code PanelStack.isAlwaysDocked}, which is the predicate that says so.
     *
     * <p>Its presence is still edit mode's rather than a field's: it is the fallback occupant of the first
     * column while the author is working and nothing else is open.
     */
    TOOLS
}
