package dev.ellipog.tasked.client;

import dev.ellipog.armature.client.ui.kit.Insets;
import dev.ellipog.armature.client.ui.kit.Layout;
import dev.ellipog.armature.client.ui.kit.Measure;
import dev.ellipog.armature.client.ui.kit.Stack;
import dev.ellipog.armature.client.ui.party.PartyRoster;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The party panel: a heading, the roster, and the actions that belong to a party.
 *
 * <h2>Where each half of it lives, and why that is a test rather than a habit</h2>
 *
 * <p>{@link PartyRoster} is Armature's, because a member, a rank and "may I remove them" are all facts
 * about a <b>team</b> — and any mod with a party wants exactly that list. This class is Tasked's,
 * because what it composes <i>around</i> the roster is one screen's: a heading whose words come from
 * this mod's language file, a Leave/Disband pair wired to <i>this</i> mod's commands, and a place in
 * the quest book's column.
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
 * a client.
 *
 * <h2>An empty party is drawn, not hidden</h2>
 *
 * <p>A player with no party sees the heading and one line saying so. Hiding the panel entirely would
 * leave a player who has been told parties exist with no way to find out that they do not have one —
 * and no place the hint about creating one could go. A panel that vanishes when the answer is "no" is
 * a panel that only speaks when it has nothing to say.
 *
 * <h2>Keys</h2>
 *
 * <p>The member rows and their Remove buttons carry {@link PartyRoster}'s own keys, because that class
 * made them and this one only places them. What is this class's is the {@code action:} namespace —
 * Leave and Disband are not per-member, so they are not the roster's to name.
 */
public final class PartyPanelLayout {

    /** The heading's height. Matches the overlay's section headings, so the two read as one design. */
    public static final int HEADING_HEIGHT = 10;

    /** The space under the heading, before the first row. */
    public static final int HEADING_TAIL = 6;

    /** The height of the "no party" line. */
    public static final int EMPTY_ADVANCE = 12;

    /** How tall an action button is. The row height everywhere else, so a column lines up. */
    public static final int ACTION_HEIGHT = 18;

    /** Between two stacked action buttons. */
    public static final int ACTION_GAP = 4;

    /** The space above the actions, after the roster. */
    public static final int ACTIONS_GAP = 10;

    /**
     * Padding at the end, as a placed row rather than a gap.
     *
     * <p>Not decoration: {@code Layout.height()} is the bottom edge of the lowest <b>slot</b>, and a
     * gap places no slot, so a trailing gap is invisible to the height — and the height is what the
     * viewport is told the content is. Written as a gap first and it reserved nothing. The same trap
     * {@code OverlayLayout.BODY_TAIL} records, and it is recorded twice on purpose: two classes hitting
     * it is evidence that it belongs in the kit's own notes rather than in one caller's.
     */
    public static final int PANEL_TAIL = 6;

    /** The heading's key. Its text is a translation key, resolved by the caller that draws it. */
    public static final String HEADING = "party:heading";

    /** The line shown when the player is in no party. */
    public static final String NO_PARTY = "party:none";

    /** Leaving a party you are in. */
    public static final String LEAVE = "action:leave";

    /** Dissolving a party you own. */
    public static final String DISBAND = "action:disband";

    private PartyPanelLayout() {
    }

    /**
     * The panel's elements, in order, as an unbuilt stack.
     *
     * <p>Unbuilt so the caller supplies the column width and the font, which is what lets a test build
     * it with a known width per character and ask the same questions the screen's own layout answers.
     *
     * <p>Most of this is generated from the roster rather than from a second description of a roster:
     * one row per member at {@link PartyRoster#ROW_HEIGHT}, with the right-hand room the roster reserves
     * for a Remove button. A member the viewer may not remove still gets that room, and
     * {@code PartyRosterTest} pins why — without it, one row's text would run under the next row's
     * button, and two rows would have different text widths for a reason nothing on screen explains.
     */
    public static Stack stack(PartyRoster roster) {
        Objects.requireNonNull(roster, "roster");

        Stack stack = Stack.stack();

        stack.row(HEADING, HEADING_HEIGHT).gap(HEADING_TAIL);

        if (!roster.isReal()) {
            // No party. One line, and the actions are <b>absent</b> rather than present-and-disabled: a
            // Leave button for somebody in no party is a control that can only refuse, and a control
            // that exists to refuse is worse than no control at all.
            return stack.row(NO_PARTY, EMPTY_ADVANCE).row(null, PANEL_TAIL);
        }

        for (PartyRoster.Member member : roster.members()) {
            stack.row(member.key(), PartyRoster.ROW_HEIGHT, removeRoom());
        }

        boolean anyAction = roster.canLeave() || roster.canDisband();
        if (anyAction) {
            stack.gap(ACTIONS_GAP);
            if (roster.canLeave()) {
                stack.row(LEAVE, ACTION_HEIGHT);
            }
            if (roster.canDisband()) {
                if (roster.canLeave()) {
                    stack.gap(ACTION_GAP);
                }
                stack.row(DISBAND, ACTION_HEIGHT);
            }
        }

        return stack.row(null, PANEL_TAIL);
    }

    /**
     * The room a row gives up on its right for a Remove button.
     *
     * <p>One expression, read by the stack that reserves it and by {@link #removeSlot} that fills it,
     * so a row's text limit and its button's position cannot come from two sums that agree today.
     */
    private static Insets removeRoom() {
        return new Insets(0, 0, PartyRoster.REMOVE_WIDTH + PartyRoster.REMOVE_INSET * 2, 0);
    }

    /**
     * The panel built in a column of {@code width}: the rows placed, and the height.
     *
     * <p>The one call a caller needs, so the height the scrollbar's range comes from and the positions
     * the controls are moved to are a single computation rather than two that agree.
     */
    public static Layout build(PartyRoster roster, int width, Measure measure) {
        Objects.requireNonNull(measure, "measure");
        return stack(roster).build(Math.max(0, width), measure);
    }

    /**
     * Where a member's Remove button goes, given the layout the panel was built into.
     *
     * <p>Looks the member's own row up by its key and delegates the placement to
     * {@link PartyRoster#removeSlot}, so the panel does not repeat a single number of the button's
     * geometry. A member who may not be removed yields null, and a caller iterating the roster can skip
     * the nulls — which is what makes it impossible to place a button whose permission was not checked.
     */
    public static dev.ellipog.armature.client.ui.kit.Slot removeSlot(PartyRoster roster, Layout layout,
                                                                    PartyRoster.Member member) {
        Objects.requireNonNull(roster, "roster");
        Objects.requireNonNull(layout, "layout");
        if (member == null) {
            return null;
        }
        return PartyRoster.removeSlot(member, layout.slot(member.key()));
    }

    /**
     * Every control the panel draws, in the order they are placed.
     *
     * <p>The list a caller iterates to create widgets, and the list a test counts. Generated rather than
     * assembled by the drawing code so the two cannot disagree about whether a row has a button.
     */
    public static List<String> controlKeys(PartyRoster roster) {
        Objects.requireNonNull(roster, "roster");
        if (!roster.isReal()) {
            return List.of();
        }

        List<String> out = new ArrayList<>();
        for (PartyRoster.Member member : roster.members()) {
            out.add(member.key());
            if (member.canRemove()) {
                out.add(member.removeKey());
            }
        }
        if (roster.canLeave()) {
            out.add(LEAVE);
        }
        if (roster.canDisband()) {
            out.add(DISBAND);
        }
        return List.copyOf(out);
    }

    /** Whether a key is one of the two actions rather than a member's row or button. */
    public static boolean isAction(String key) {
        return LEAVE.equals(key) || DISBAND.equals(key);
    }
}
