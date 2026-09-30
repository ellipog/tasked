package dev.ellipog.tasked.client;

import dev.ellipog.armature.client.ui.kit.Insets;
import dev.ellipog.armature.client.ui.kit.Layout;
import dev.ellipog.armature.client.ui.kit.Measure;
import dev.ellipog.armature.client.ui.kit.Slot;
import dev.ellipog.armature.client.ui.kit.Stack;
import dev.ellipog.armature.client.ui.party.PartyRoster;
import dev.ellipog.tasked.net.PartySnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The party panel: a title, the roster, and the actions that belong to a party.
 *
 * <h2>The whole panel, not just the roster</h2>
 *
 * <p>This used to describe only the part <i>below</i> the action rows, and the screen described the rest
 * itself — the title, where the actions went, and how tall the card had to be. That split is what the
 * reported fault was made of, and it is worth being exact about it because the two halves each looked
 * right:
 *
 * <pre>
 * PartyPanelLayout:   title (11) + gap (7) + one member row (18)                  = 36
 * the screen's sum:   one action row (18) + gap (12) + one member row (18)        = 48
 * </pre>
 *
 * <p>The second sum is missing the title and the gap under it, so the card was built <b>sixteen pixels
 * too short</b> — and the drawing, which skips a row that would fall past the body, skipped exactly the
 * rows that mattered. A party of one showed "Counts: one_member" and a Change button, and no members.
 * The arithmetic was not wrong by a mistake; it was wrong because there were two of it.
 *
 * <h2>One description, and the card is sized from it</h2>
 *
 * <p>So this is now the <b>whole</b> panel, actions included, and {@link #build} returns the one
 * {@link Layout} that says both where every row goes and how tall the content is. The screen sizes the
 * card from {@code layout.height()} and places the rows from the same object, so a term added here
 * cannot be forgotten there — there is no "there" to forget it in.
 *
 * <p>That is the same property the kit exists for, one level up: {@code Stack.build} places and measures
 * in one call, and this composes a stack whose height is the card's body. A panel that grows when a
 * member joins grows because the layout got taller, not because somebody adjusted a constant.
 *
 * <h2>Where each half of it lives, and why that is a test rather than a habit</h2>
 *
 * <p>{@link PartyRoster} is Armature's, because a member, a rank and "may I remove them" are all facts
 * about a <b>team</b> — and any mod with a party wants exactly that list. This class is Tasked's,
 * because what it composes <i>around</i> the roster is one screen's: a heading whose words come from this
 * mod's language file, rows whose buttons are wired to <i>this</i> mod's commands, and a place in the
 * quest book's column.
 *
 * <p>That is the opposite answer from {@code PartyMode}, which is Tasked's because a mode combines
 * <i>oak logs</i> and only a quest has an opinion about oak logs. Applying the same test twice and
 * getting two different answers is the point of having a test.
 *
 * <h2>Why this is not inside the screen</h2>
 *
 * <p>Because {@code QuestBookScreen} extends {@code Screen} and needs a running Minecraft to
 * instantiate, so nothing in it can be asserted on. The height this produces is what the panel's own
 * scroll range comes from — the same argument {@code OverlayLayout} records — so
 * {@code PartyPanelLayoutTest} can ask how tall the panel is, and that its rows do not overlap, without
 * a client. It can also ask the question this round was about, which no screenshot could answer for
 * every party size: <b>is every member row inside the body the card was built for</b>.
 *
 * <h2>An empty party is drawn, not hidden</h2>
 *
 * <p>A player with no party sees the title and two lines saying so, one of which names the way out. A
 * panel that vanished when the answer was "no" would leave a player who has been told parties exist with
 * no way to find out that they do not have one — and no place the Create button could go.
 *
 * <h2>Keys</h2>
 *
 * <p>The member rows and their Remove buttons carry {@link PartyRoster}'s own keys, because that class
 * made them and this one only places them. What is this class's is the {@code action:} namespace —
 * Leave and Disband are not per-member, so they are not the roster's to name — plus the four keys the
 * panel draws itself, which are named here so the drawing has one place to look them up.
 */
public final class PartyPanelLayout {

    // ------------------------------------------------------------------
    // Metrics
    // ------------------------------------------------------------------

    /** The title row's height. A line of text, with room for the member count beside it. */
    public static final int TITLE_HEIGHT = 11;

    /** The space under the title, before the roster or the empty state. */
    public static final int TITLE_TAIL = 7;

    /** The height of one line in the empty state. */
    public static final int EMPTY_ADVANCE = 11;

    /** Between the empty state's two lines. Smaller than {@link #TITLE_TAIL}: they are one thought. */
    public static final int EMPTY_GAP = 3;

    /** How tall an action row is. The row height everywhere else, so a column lines up. */
    public static final int ACTION_HEIGHT = 18;

    /** Between two stacked action rows. */
    public static final int ACTION_GAP = 3;

    /** The space either side of the rule that separates the roster from the actions. */
    public static final int SECTION_GAP = 7;

    /**
     * How wide an action row's button is.
     *
     * <p>Wider than the footer's Leave and Disband, because these labels are "Create", "Accept" and
     * "Invite" rather than a five-letter word, and because a row's button sits against the card's edge
     * rather than in a footer where three things share one line. Sized from the longest of them at the
     * same six-pixels-a-character measurement the footer uses.
     */
    public static final int ROW_BUTTON = 62;

    /** Kept between a row's right edge and the button that sits in its reserved strip. */
    public static final int ROW_BUTTON_INSET = 2;

    // ------------------------------------------------------------------
    // A member row's interior
    // ------------------------------------------------------------------

    /**
     * The six numbers that place a member row's own contents.
     *
     * <h2>Why they are here rather than in the screen</h2>
     *
     * <p>Because they are the row's composition, and this class is where a row's composition lives: a
     * portrait, the marker for who is connected, a name, and a rank. They were private to
     * {@code QuestBookScreen}, which put a drawing metric in the one class in either mod that cannot be
     * asked anything — so the preview could not draw a member row without a second copy of all six, and
     * a second copy of a number is the fault this project hunts. Here they are readable by the screen,
     * the tests and the dump alike.
     *
     * <p>The portrait box is twelve rather than the sixteen a Minecraft item takes, because a face is
     * an 8×8 skin crop scaled up and twelve is the size that reads as a portrait rather than as an
     * inventory slot; {@code NAME_DROP} is optical rather than arithmetic, and its own note says why.
     */
    public static final int HEAD_BOX = 12;

    /** How far a portrait sits from its row's left edge, and the gap between it and the marker. */
    public static final int HEAD_INSET = 3;
    public static final int HEAD_GAP = 3;

    /** The online marker before a member's name: a small square, and the gap after it. */
    public static final int STATUS_DOT = 3;
    public static final int STATUS_GAP = 4;

    /**
     * How far a member's name sits below its row's own centre.
     *
     * <p>Optical rather than arithmetic: the line box a label is drawn in carries descender space under
     * the baseline, so text centred by that box reads a pixel or two high — which it did, beside a face
     * centred on its own rectangle. The report was "moved down a teeny tiny bit, like 1 or 2 pixels",
     * and two is where it stopped reading high.
     */
    public static final int NAME_DROP = 2;

    // ------------------------------------------------------------------
    // Keys
    // ------------------------------------------------------------------

    /**
     * The title row, named by its translation key.
     *
     * <h2>Why these three are named by their translation key and the rest are not</h2>
     *
     * <p>Because for these three the translation key <i>is</i> the string: this class and the screen are
     * the only two things that ever see them, and one of the two has to name them. A layout key like
     * {@code "party:title"} would need a second table in the screen mapping it back to the one thing it
     * can possibly be — three rows, three right answers, and so three chances to be wrong.
     *
     * <p>The member rows and the action rows are a different case and keep their own namespaces: a
     * member's text comes from the roster and an action's from the caller's own row list, so neither is
     * this class's to name.
     *
     * <p>One sentence was corrected here rather than left: it said "the screen also puts the member
     * count at its right", and the screen does not — the count is in the party button's tooltip, where a
     * roster of nine is written out in words. A preview drawn from that sentence would have invented a
     * number.
     */
    public static final String TITLE = "tasked.screen.party.title";

    /** The line shown when the player is in no party. See {@link #TITLE} for the naming. */
    public static final String NO_PARTY = "tasked.screen.party.none";

    /** The second line of the empty state: what to do about it. See {@link #TITLE}. */
    public static final String HINT = "tasked.screen.party.hint";

    /** The rule between the roster and the actions. One pixel tall, drawn across the body. */
    public static final String RULE = "party:rule";

    /** Leaving a party you are in. */
    public static final String LEAVE = "action:leave";

    /** Dissolving a party you own. */
    public static final String DISBAND = "action:disband";

    private PartyPanelLayout() {
    }

    /**
     * One row of the panel: a label on the left, and optionally a button on the right.
     *
     * <h2>Why a record rather than a widget per case</h2>
     *
     * <p>Because every state of this panel -- no party, an invitation waiting, a roster -- is "a list of
     * rows with an action beside some of them", and the differences between them are what the rows
     * <i>say</i> rather than how they are placed. So the placement is one loop over this list, and a
     * state is a different list.
     *
     * @param key         what places this row
     * @param label       what the row says, drawn at its left
     * @param buttonLabel the button's label, or null for a row with no action
     * @param command     the command the button sends, or null when there is no button
     */
    public record Action(String key, String label, String buttonLabel, String command) {

        /**
         * A row with a button beside it.
         *
         * <p>A factory rather than the canonical constructor at the call site, because a caller writing
         * {@code new Action(key, label, button, command)} four positional arguments deep is one
         * transposition away from a button labelled with a command. This says which of the two strings
         * is which.
         *
         * <p>Every row this panel has today carries a button. There was a {@code plain} factory here for
         * a row that only reads, and it went when it turned out that the two rows which looked like
         * candidates both want a control: the mode row wants a Change, and an invitation wants an
         * Accept. A factory with no callers is a guess about a caller that may never exist.
         */
        public static Action button(String key, String label, String buttonLabel, String command) {
            return new Action(key, label, buttonLabel, command);
        }

        /**
         * Whether this row has a control at all.
         *
         * <p>A button needs both halves: a label to draw and a command to send. A row with one and not
         * the other is a control that either cannot be read or cannot work, so this asks for both
         * rather than trusting the caller to have supplied them together.
         */
        public boolean hasButton() {
            return buttonLabel != null && command != null;
        }
    }

    // ------------------------------------------------------------------
    // The panel
    // ------------------------------------------------------------------

    /**
     * The panel's elements, in order, as an unbuilt stack.
     *
     * <p>Unbuilt so the caller supplies the column width and the font, which is what lets a test build
     * it with a known width per character and ask the same questions the screen's own layout answers.
     *
     * <p>The roster's rows are {@link PartyRoster#composition() the roster's own}, nested in whole. That
     * is not tidiness: this file used to build them itself, from the same constants, which is two
     * descriptions of one thing — and it left Armature's version with no callers, so its inset for the
     * Remove button's column was written out twice and only one of them was ever exercised.
     *
     * <p>The gaps go <b>before</b> each row after the first rather than after each row, so the layout does
     * not end with one. A trailing gap places no slot, so {@code Layout.height()} — the bottom edge of the
     * lowest slot — would not count it, and the card would be built shorter than the content it holds.
     * That is the fault this class was rewritten for, arriving by a second route, so it is worth the
     * sentence.
     *
     * @param roster  the roster to draw. Its {@code isReal} decides whether members or the empty state
     *                appear, which is not the same as its member count — a party of one is a party
     * @param actions the rows below the roster: Create, an Accept per invitation, Invite per online
     *                player, and the mode row. Empty is allowed and means no rule is drawn either
     */
    public static Stack stack(PartyRoster roster, List<Action> actions) {
        Objects.requireNonNull(roster, "roster");
        Objects.requireNonNull(actions, "actions");

        Stack stack = Stack.stack();

        stack.row(TITLE, TITLE_HEIGHT).gap(TITLE_TAIL);

        if (roster.isReal()) {
            // The roster's own composition, nested rather than re-listed. This file used to write that
            // loop itself -- one row per member, the gap before each row after the first, and its own
            // copy of the inset that reserves a Remove button's column -- which left Armature's
            // `PartyRoster.composition` with no callers at all and put one inset in two places. Two
            // descriptions of one thing is the fault this codebase hunts; the kit was missing
            // `Stack.append`, and that is the gap the duplicate grew in.
            stack.append(roster.composition());
        }
        else {
            // Two lines rather than one, because the second is the only place a player is told what to do
            // about it. The old empty state said "You are not in a party" and stopped, which left the
            // Create button to be found by experiment.
            stack.row(NO_PARTY, EMPTY_ADVANCE).gap(EMPTY_GAP).row(HINT, EMPTY_ADVANCE);
        }

        if (!actions.isEmpty()) {
            // A rule rather than a gap, because the two lists are different kinds of thing: the roster is
            // who you are with, and everything below it is something you can do. A rule is what says so.
            //
            // Written as a one-pixel row with a key rather than as `Stack.divider`, which places a null
            // key: the drawing looks its slots up by key, and a null key is by design unfindable. A rule
            // nobody can look up is a rule nobody can draw.
            stack.gap(SECTION_GAP).row(RULE, 1).gap(SECTION_GAP);

            for (int i = 0; i < actions.size(); i++) {
                if (i > 0) {
                    stack.gap(ACTION_GAP);
                }
                Action action = actions.get(i);
                // Room for a button only where there is one. A row that reserved it anyway would have its
                // label stopping short of the card's edge for no reason a reader could see — the same
                // distinction the roster does *not* make, and the reason is that a roster is a column of
                // identical rows where a mismatch is visible and an action list is not.
                stack.row(action.key(), ACTION_HEIGHT, action.hasButton() ? actionRoom() : Insets.NONE);
            }
        }

        return stack;
    }

    /**
     * The panel built in a column of {@code width}: the rows placed, and the height.
     *
     * <p>The one call a caller needs, and it is now the one call for the <b>whole</b> panel rather than
     * for its lower half. The height it returns is what the card is built from, so the box and the rows
     * inside it cannot come from two sums — see the class note for what they did when they could.
     */
    public static Layout build(PartyRoster roster, List<Action> actions, int width, Measure measure) {
        Objects.requireNonNull(measure, "measure");
        return stack(roster, actions).build(Math.max(0, width), measure);
    }

    /** The same, for an action row's own button. See {@link #ROW_BUTTON}. */
    private static Insets actionRoom() {
        return new Insets(0, 0, ROW_BUTTON + ROW_BUTTON_INSET * 2, 0);
    }

    // ------------------------------------------------------------------
    // The rows
    // ------------------------------------------------------------------

    /**
     * The rows the panel shows, given the roster and what the client was last told.
     *
     * <h2>What this does not do, and the reason it is worth stating</h2>
     *
     * <p>It does not decide permissions. Every command behind these buttons re-checks on the server --
     * that is the whole design -- so a row offered wrongly is a refusal rather than a wrong change. The
     * panel's job is to offer the useful thing, not to be the authority.
     *
     * <p>Offers <b>Invite</b> for every online player who is not already in the party, including players
     * already invited: the server refuses a duplicate, and hiding the row would mean the panel had to
     * know who was already invited, which is a fact it would then have to keep in step. A <b>member</b>
     * is excluded, because the roster already says who they are and the row could only fail.
     *
     * <h2>Why this is here rather than in the screen</h2>
     *
     * <p>Because it is a rule about the panel's <i>content</i>, and a screen cannot be asked anything:
     * this method lived in {@code QuestBookScreen} as a private one, so the only way to see what a party
     * of three with two players online offers was to open the book and look. It is a pure function of
     * the roster, the snapshot and the viewer's name, so it can be asserted and drawn without a game --
     * which is what lets the preview show the rows the screen would.
     *
     * @param self the viewer's own name, so their own row is not offered as somebody to invite. Empty
     *             for a caller with no player, which offers every online player and no one twice
     */
    public static List<Action> actions(PartyRoster roster, PartySnapshot snapshot, String self) {
        Objects.requireNonNull(roster, "roster");
        Objects.requireNonNull(snapshot, "snapshot");

        List<Action> rows = new ArrayList<>();
        String me = self == null ? "" : self;

        if (!roster.isReal()) {
            // Creating needs a name and there is no text field in the kit, so the name is derived from
            // the player's own. **Every character in it has to survive Brigadier**, and the first
            // version did not: it built `Ellipog's party`, and an apostrophe is not a character the
            // parser is obliged to accept in an unquoted argument -- so the button produced a command
            // the server refused.
            String base = me.isEmpty() ? "My" : sanitise(me);
            // "Start a party" rather than "Not in a party": the layout draws the empty state's own line
            // saying that, and a row repeating it would say the same thing twice on one panel.
            rows.add(Action.button("create", "Start a party", "Create",
                    "/tasked party create " + base + " party"));

            for (PartySnapshot.Invite invite : snapshot.invites()) {
                rows.add(Action.button("accept:" + invite.teamId(),
                        "Invited to " + invite.teamName(), "Accept", "/tasked party accept"));
            }
        }
        else {
            // The counting rule, as a line of text rather than as the cycling button this used to be.
            // The report was "remove the counts thing, and change button since it doesnt do anything" --
            // and the reason it read as doing nothing is that the press closed the panel, so the new
            // label arrived after the panel was gone. The rule is still set by `/tasked party mode`;
            // what is here is the answer, so a party can see which rule is in force without a command.
            //
            // A row with no button rather than a fourth control: `Action.hasButton()` is false when
            // either half is missing, and the layout reserves no button strip for a row without one.
            rows.add(new Action("mode", "Counts: " + snapshot.modeOr().id(), null, null));
        }

        for (String name : snapshot.online()) {
            // Neither the viewer nor a member is offered an invitation: a member is already in the
            // party, so the row would be a button whose only possible answer is the server's refusal --
            // "offer the useful thing" is the rule this method states, and that is not it.
            //
            // The membership test is the roster's own, and the roster is the same object the member rows
            // are drawn from, so the two cannot come to disagree about who is in the party. Until this
            // was written the method's own doc said "not already in the party" and the code asked only
            // about the viewer, so a party of three were each offered invitations to the party they were
            // in -- visible in the preview's `trio` state, which is how it was found.
            if (name.equalsIgnoreCase(me) || isMember(roster, name)) {
                continue;
            }
            rows.add(Action.button("invite:" + name, name, "Invite",
                    "/tasked party invite " + name));
        }
        return List.copyOf(rows);
    }

    /** Whether a name is one of the roster's own members. Compared by name, which is what the rows hold. */
    private static boolean isMember(PartyRoster roster, String name) {
        for (PartyRoster.Member member : roster.members()) {
            if (member.name().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A string that can be an unquoted command argument.
     *
     * <p>Letters, digits, spaces, underscores and hyphens; everything else becomes an underscore. That
     * is a superset of what Brigadier's unquoted argument accepts and a subset of what a Minecraft name
     * can contain, which is the whole point: the set of characters a name <i>may</i> hold and the set an
     * argument may hold are not the same, and the panel builds one from the other.
     */
    private static String sanitise(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            out.append(Character.isLetterOrDigit(c) || c == ' ' || c == '_' || c == '-' ? c : '_');
        }
        return out.toString();
    }

    /**
     * Where a member's Remove button goes, given the layout the panel was built into.
     *
     * <p>Looks the member's own row up by its key and delegates the placement to
     * {@link PartyRoster#removeSlot}, so the panel does not repeat a single number of the button's
     * geometry. A member who may not be removed yields null, and a caller iterating the roster can skip
     * the nulls — which is what makes it impossible to place a button whose permission was not checked.
     */
    public static Slot removeSlot(PartyRoster roster, Layout layout, PartyRoster.Member member) {
        Objects.requireNonNull(roster, "roster");
        Objects.requireNonNull(layout, "layout");
        if (member == null) {
            return null;
        }
        return PartyRoster.removeSlot(member, layout.slot(member.key()));
    }

    /**
     * Where an action row's button goes, given the layout the panel was built into.
     *
     * <h2>Why this mirrors {@link #removeSlot} rather than being its general case</h2>
     *
     * <p>Because the two reserve their room in different places and for different reasons, and folding
     * them together would mean one of the two reading a number that belongs to the other. A member row's
     * button is placed by {@code PartyRoster}, which owns that geometry because it owns the roster; an
     * action row's is placed here, because the action rows are this class's composition and Armature has
     * no opinion about them.
     *
     * <p>The shape is deliberately identical — look up the row, land inside the strip it reserved, return
     * null when there is no button — so a caller iterating rows does not have to remember which kind it is
     * holding. What differs is only where the arithmetic lives.
     *
     * <p>Returns null for a row with no button <b>and</b> for a key the layout does not hold, which a
     * caller treats the same way: there is nothing to place.
     */
    public static Slot actionSlot(Layout layout, Action action) {
        Objects.requireNonNull(layout, "layout");
        if (action == null || !action.hasButton()) {
            return null;
        }
        Slot row = layout.slot(action.key());
        return row == null ? null : buttonStrip(row);
    }

    /**
     * The rectangle an action row's button occupies, given the row's own slot.
     *
     * <h2>Why this is a function of the row rather than of the layout</h2>
     *
     * <p>Because the scroll view needs to derive a control's rectangle from the slot the layout holds,
     * once per placement, without knowing which row it is looking at. Handing this to
     * {@code ScrollView.put} at registration is what keeps a scrolled button on its own row: the
     * placement and the drawing ask the same slot of the same layout, and this is the only expression of
     * where the button sits inside it.
     *
     * <p>The strip starts at the row's <b>right edge plus the inset</b>, because the row's own inset
     * reserved that room: a {@code STRETCH} row is narrowed by its insets, so the button belongs outside
     * the slot and inside the gap the slot left for it. That is the opposite of
     * {@code PartyRoster.removeSlot}, whose button sits inside the row it narrows, and the two are
     * deliberately not folded together — see {@link #actionSlot}.
     */
    public static Slot buttonStrip(Slot row) {
        Objects.requireNonNull(row, "row");
        int width = Math.min(ROW_BUTTON, Math.max(0, row.width()));
        return new Slot(row.key(), row.right() + ROW_BUTTON_INSET, row.y(), width, row.height());
    }

    /** Whether a key is one of the two footer actions rather than a row or a button. */
    public static boolean isAction(String key) {
        return LEAVE.equals(key) || DISBAND.equals(key);
    }
}
