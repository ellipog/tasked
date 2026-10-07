package dev.ellipog.tenet.client;

/**
 * Which panel is open: a quest, the item picker, the rewards inbox, and so on.
 *
 * <h2>An identity, not a presentation</h2>
 *
 * <p>This names <b>what</b> is open and says nothing about <b>how</b> it is drawn. That sentence was
 * written when there were two presentations to choose between — a centred card and a docked column — and
 * it is what made deleting the card cheap: every kind already read the same constant, so the constant is
 * all that had to change. There is one presentation now, and this is still an identity rather than a
 * shape.
 *
 * <p>The name is not {@code Overlay} for the same reason it never was: the old name described the card,
 * and every kind is a rail.
 *
 * <h2>Why it is a package-level type rather than nested in the screen</h2>
 *
 * <p>Because the rules about which kind may occupy which rail are asserted without a client — see
 * {@code PanelStack} and {@code PanelStackTest} — and a nested {@code private} enum cannot be named by a
 * test. It was nested and private while the screen was its only reader, which was true then and is not
 * now. Nothing else about it changed in the move: the constants and their notes are as they were.
 *
 * <p>{@link #NONE} is the <i>absence</i> of a panel rather than one of them, which is why it is the
 * value every guard is written against and the value a screen with nothing open holds.
 */
public enum PanelKind {

    /** Nothing is open: no rail, no panel. The value a screen with nothing showing holds. */
    NONE,

    /** The quest itself — the reader, and the editor in edit mode. What a node click opens. */
    QUEST,

    PARTY,

    /**
     * The item picker on its own, for a field that is not a quest's.
     *
     * <p>It used to be a page of the quest panel and nothing else, because every field it served
     * belonged to a quest. The Chapter tab's icon is the first caller outside that panel, and a picker
     * that demanded a quest to open would have meant a second picker -- so it became a rail of
     * its own, drawn with the same surface and the same list. See {@code QuestBookScreen#pickTarget}.
     */
    PICKER,

    /**
     * The texture picker: the pack's own PNG files, for the canvas image's Texture row.
     *
     * <p>Its own rail rather than a page of the picker's, because the two choose different
     * things from different lists: an item is a registry entry with a stack to draw, a texture is a
     * file with a picture to probe, and the item picker's catalogue, "current" row and commit all
     * belong to a field of a quest or a chapter's identity. Sharing the panel's shape while sharing
     * none of that state is what keeps each picker's rules testable on its own -- see
     * {@code TexturePicker} for the rules and {@code QuestBookScreen#drawTexturePicker} for the drawing.
     */
    TEXTURE,

    /**
     * A choice reward's entries, waiting for the player's answer.
     *
     * <p>A rail of its own because the question has no panel behind it: the offer arrives when
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
     * server's to state (see {@code ClientQuestCache.canClaimFor}), and a panel is where a player can
     * read them before spending them. Opened from the header, between Party and Close.
     */
    REWARDS,

    /**
     * The naming panel: a title and an id for a chapter or a group being made, renamed or duplicated.
     *
     * <p>A rail of its own rather than a page of the quest panel, because what it names may have no
     * quest and no chapter behind it at all -- a new group, or a chapter about to be created. It is
     * the one panel opened from the sidebar rather than from a quest, and the one whose fields are
     * read without an editor session: the ops it sends are structural.
     */
    NAMING,

    /**
     * The player's own settings: the theme list, the corner radius and the Motion switch.
     *
     * <p>A rail of its own rather than a page of the tools dock, which is the author's -- the
     * settings it holds are every player's, and they were behind the edit permission until this
     * existed. See {@code SettingsLayout} for the rows and {@code Look} for what they change.
     */
    SETTINGS,

    /**
     * The reward tables, for a reward's table field: a list of them to choose from, with the rows
     * that make and manage one.
     *
     * <p>A rail rather than a page of the panel that opened it, because it is a list of the pack's own
     * assets rather than a field of the quest being edited — the same argument the item picker's own
     * rail makes.
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
     * <h2>Why it is a kind at all, when it is not a panel you open</h2>
     *
     * <p>Because it is a rail, and one rail with two owners is how the rails came to disagree: this dock and
     * a panel once shared the first one, positioned, routed and revealed by two different sets of
     * arithmetic, so swapping between them moved the edge. Being a kind is what makes it one object to the
     * rest of the screen — the same rail, the same press routing, the same grip, the same arrival — and it
     * is what lets a picker opened from its Chapter tab be filed <i>beside</i> it.
     *
     * <h2>The one kind that is never a panel column</h2>
     *
     * <p>{@code PanelStack} refuses to file it into one: {@code afterOpen} and {@code asRoot} both answer
     * with {@code withDock}, so asking for the dock opens the dock's own rail rather than putting the
     * author's tools where a panel goes. That is what makes the dock the one thing a panel transition
     * cannot displace — it was the fallback occupant of the first column before, which is exactly why
     * opening a panel used to hide it and closing that panel used to bring it back in the same rectangle.
     *
     * <p>A "tools screen" was tried first and rejected: the tool exists to watch the canvas it floats over,
     * so the dock must not be able to become something the canvas is not beside.
     *
     * <p>Its presence is the author's: edit mode opens the pill that latches it, and nothing else does.
     */
    TOOLS
}
