package dev.ellipog.tasked.client.dev;

import dev.ellipog.tasked.quest.QuestReward;
import dev.ellipog.tasked.quest.reward.AdvancementReward;
import dev.ellipog.tasked.quest.reward.CommandReward;
import dev.ellipog.tasked.quest.reward.CustomReward;
import dev.ellipog.tasked.quest.reward.ItemReward;
import dev.ellipog.tasked.quest.reward.RewardAutoClaim;
import dev.ellipog.tasked.quest.reward.StageReward;
import dev.ellipog.tasked.quest.reward.TableReward;
import dev.ellipog.tasked.quest.reward.XpReward;

import java.util.ArrayList;
import java.util.List;

/**
 * The controls one entry's fold draws, chosen by the entry's own reward type.
 *
 * <h2>Why this is a list of controls and not a form</h2>
 *
 * <p>An entry's fold had two cases and neither was general: an item drew an id box and a count stepper,
 * a nested table drew an "Edit" chip, and <b>every other type drew nothing at all</b> — a fold opened
 * onto eighteen pixels of empty band, so an experience, command, advancement, stage or custom entry had
 * no editable field anywhere in the table editor. Those fields exist, the loader reads them and the
 * grant obeys them; only the panel could not reach them.
 *
 * <p>The card's own form ({@code EntryFormLayout}) is not reused here, deliberately. It is built around
 * a badge, a drag grip, a duplicate control, a remove control and a conditions list — none of which a
 * table entry has, two of which a table entry must <i>not</i> have — and it sizes itself from a card's
 * content column rather than a row's. What is shared is the one thing worth sharing: the field
 * <i>names</i>, which come from the reward type itself. Everything a type allows is reachable from
 * here, and {@code TableRowFieldsTest} walks the registry to say so, because a field this switch forgets
 * is a field no panel can set — which is the fault this class was written for.
 *
 * <h2>Game-free</h2>
 *
 * <p>It reads a decoded reward and returns rectangles-to-be: no renderer, no screen, no item registry.
 * So what each type offers is a thing a test can assert, and the drawing is a switch over what it
 * returns.
 */
public final class TableRowFields {

    private TableRowFields() {
    }

    /**
     * How a control is drawn, pressed, and — for the ones a person types into — turned back into JSON.
     *
     * <p>The numeric kinds are split because the file is not a string: {@code count} and
     * {@code permissionLevel} are whole numbers and a weight is not, and a panel that wrote {@code 2.0}
     * into a count field would be writing a value the format does not have. It would still decode, which
     * is exactly why it needs to be a kind rather than a hope.
     */
    public enum Kind {
        /** An item id, set by the picker rather than typed. */
        ITEM,
        /** A string or an id, typed. */
        TEXT,
        /** A whole number. */
        INT,
        /** A number that may have a fraction, and may not be negative. */
        DECIMAL,
        /** True or false, as a switch. */
        FLAG,
        /** One of a few words, cycled. */
        CHOICE,
        /** A nested table: opens the editor on it. */
        TABLE_OPEN,
        /** The entry's whole reward as JSON, for a type this build has no form for. */
        RAW
    }

    /**
     * One control in a fold.
     *
     * @param kind  how it is drawn and what its text commits as
     * @param field the reward's own field this writes — {@code "amount"}, {@code "levels"} — or empty
     *              for a control that writes no field, like the nested table's chip
     * @param label what the control is called, drawn inside or beside it
     * @param hint  the sentence a hover shows. Every one of these is a sentence about the format, not a
     *              restatement of the label: a fold has no room for a second word that says nothing.
     */
    public record Control(Kind kind, String field, String label, String hint) {
    }

    /** One line of a fold: the controls drawn side by side on it. Never empty. */
    public record Line(List<Control> controls) {

        public Line {
            controls = List.copyOf(controls);
        }
    }

    /**
     * The lines one reward's fold draws.
     *
     * <p>Never empty, and never without a way to edit the reward's own fields: a type this build has no
     * form for gets the raw-JSON row, because a fold that opens onto nothing is the state this class
     * exists to end — and an addon's type is in that position the day it registers.
     *
     * <p>The fallback is chosen by whether the type has fields of its own, not by whether the composed
     * list came out empty. It did once, and the effect was the opposite of the intent: every type has the
     * three base mechanics, so the list was never empty, so the raw row never appeared — and an addon's
     * reward offered "when it is given" and nothing else. The test that walks the registry found it.
     */
    public static List<Line> lines(QuestReward reward) {
        List<Control> controls = new ArrayList<>(ownFields(reward));
        if (controls.isEmpty()) {
            controls.add(new Control(Kind.RAW, "", "JSON",
                    "this entry's reward as it is stored - a type this build has no form for"));
        }
        controls.addAll(baseMechanics());
        return pack(controls);
    }

    /** How many lines a fold draws, which is what the layout's height arithmetic is given. */
    public static int lineCount(QuestReward reward) {
        return lines(reward).size();
    }

    /**
     * The fields one reward type has, in the order a reader wants them: what it gives first, then how it
     * behaves.
     *
     * <p>Empty for a type this build has no form for, which is what {@link #lines} reads to choose the
     * raw row. The base mechanics are <b>not</b> here: they belong to every type, so appending them in
     * this method made the empty list the caller tests for impossible.
     */
    private static List<Control> ownFields(QuestReward reward) {
        List<Control> out = new ArrayList<>();
        switch (reward) {
            case ItemReward ignored -> {
                out.add(new Control(Kind.ITEM, "item", "Item",
                        "the item to give; the picker keeps the data of the one you pick"));
                out.add(new Control(Kind.INT, "count", "Count",
                        "how many"));
                out.add(new Control(Kind.INT, "randomBonus", "Extra",
                        "up to this many more, rolled at random on top of the count"));
                out.add(new Control(Kind.FLAG, "onlyOne", "Only one",
                        "skip it if the player already carries this item"));
            }
            case XpReward ignored -> {
                out.add(new Control(Kind.INT, "amount", "Give",
                        "how much experience to give"));
                out.add(new Control(Kind.FLAG, "levels", "Levels",
                        "give levels rather than points"));
            }
            case CommandReward ignored -> {
                out.add(new Control(Kind.TEXT, "command", "Command",
                        "run as the player when this is collected, without the leading slash"));
                out.add(new Control(Kind.INT, "permissionLevel", "As",
                        "the permission level to run it at; 2 is a command block's"));
                out.add(new Control(Kind.FLAG, "silent", "Quiet",
                        "do not say in chat that it ran"));
            }
            case AdvancementReward ignored -> {
                // Typed rather than picked, and that is the one thing this fold does worse than the card:
                // the card's advancement list is a search picker whose commit writes a *quest* field, and
                // pointing it at a table would need the same second commit path the item picker has. The
                // id is the whole value and the validator checks it, so the field is editable here; the
                // picker is the card's.
                out.add(new Control(Kind.TEXT, "advancement", "Advancement",
                        "the advancement's id, as a file spells it - minecraft:story/root"));
                out.add(new Control(Kind.TEXT, "criterion", "One criterion",
                        "award one criterion of it instead of the whole advancement"));
            }
            case CustomReward ignored -> out.add(new Control(Kind.TEXT, "id", "Id",
                    "the id another mod registered for its own reward"));
            case StageReward ignored -> {
                out.add(new Control(Kind.TEXT, "stage", "Stage",
                        "the stage to set when this is collected"));
                out.add(new Control(Kind.FLAG, "remove", "Take away",
                        "take the stage away instead of granting it"));
            }
            case TableReward nested -> {
                // Only for a named table: an inline one has no id to open, and the card is where it is
                // replaced. A choice entry lands here too -- the validator refuses one, and until then
                // this at least says what it is.
                if (nested.tableId().isPresent()) {
                    out.add(new Control(Kind.TABLE_OPEN, "", "Table",
                            "open this entry's table in the editor"));
                }
                else {
                    out.add(new Control(Kind.TEXT, "table", "Table",
                            "this entry rolls a table written inline; name one to point it at a file"));
                }
            }
            default -> {
                // An addon's type, or one registered after this switch was written. Empty here, and
                // `lines` turns that into the raw row.
            }
        }
        return out;
    }

    /**
     * The three switches every reward carries, in the words the reward registry uses.
     *
     * <p>{@code team} is deliberately absent, and so is it absent from the registry's own form: it is an
     * optional boolean rather than a switch — "unset" means the questline's default — and a control that
     * writes true or false cannot express that. The panel's field list is where that one is set.
     */
    private static List<Control> baseMechanics() {
        return List.of(
                new Control(Kind.CHOICE, "auto", "Given",
                        "when it is handed over: default follows the quest's own setting, enabled gives "
                                + "it on completion with a notification, no_toast gives it silently, "
                                + "invisible gives it with no trace at all, disabled waits for a claim"),
                new Control(Kind.FLAG, "excludeFromClaimAll", "Claim separately",
                        "Claim all leaves this one for its own press"),
                new Control(Kind.FLAG, "ignoreRewardBlocking", "Ignore blocking",
                        "give it even while the team's rewards are being held"));
    }

    /**
     * The words the {@code auto} cycle steps through, in order: the format's own spellings, from the enum
     * that owns them.
     *
     * <p>A list written here was three of the five words, which is how a press on the shipped
     * {@code no_toast} reward became {@code default}. {@code TableRowFieldsTest} holds this equal to the
     * registry's own options <i>and</i> to {@link RewardAutoClaim#wireValues()}, so the ring cannot lag
     * the format again.
     */
    public static final List<String> AUTO_VALUES = RewardAutoClaim.wireValues();

    /**
     * What a cycling control shows for its value: the word, or the first word of its ring when it has none.
     *
     * <p>An absent {@code auto} is not a missing value — it is the field saying <i>default</i>, which is
     * one of the three words the format takes and the value the questline's own setting is applied to.
     * Drawn as an empty box with an options mark it read as a broken control, which is how it came back
     * from a screenshot: a blank box beside "Levels" and beside "Quiet", with nothing to say what it was.
     *
     * <p>{@code auto} is the only cycling control a fold has, and the ring above is its list of words.
     * <b>This is not a general rule for every choice field</b>: it holds here because one of {@code auto}'s
     * own words is "default", which is what absence means. The card's form has three more
     * ({@code match} on a task and a condition, {@code observeType}) whose first option is <i>not</i>
     * obviously what their absence means — {@code match} offers "none" first, and nothing in the code says
     * an absent match is no matching — so those still draw an empty box, and teaching them the rule needs
     * each type's codec default read first. A placeholder that guessed would print a word the file does not
     * mean, which is worse than a blank box.
     */
    public static String choiceText(String value) {
        String word = value == null || value.isEmpty() ? AUTO_VALUES.get(0) : value;
        return dev.ellipog.tasked.quest.EditorSpecs.label(word);
    }

    /**
     * The controls packed into lines: two at most, and only when both are narrow.
     *
     * <p>Two rather than "as many as fit", and that is a decision rather than a limit: a fold's cells are
     * equal columns, so a control's width depends on how many share its line — and a stepper that is a
     * different size in two entries of one table is a control an author has to re-read. A wide control
     * (an id, a command, the raw text) always takes its own line, because half a row is not enough to
     * read an id in.
     */
    private static List<Line> pack(List<Control> controls) {
        List<Line> lines = new ArrayList<>();
        List<Control> line = new ArrayList<>();
        for (Control control : controls) {
            if (!narrow(control.kind())) {
                flush(lines, line);
                lines.add(new Line(List.of(control)));
                continue;
            }
            if (line.size() == 2) {
                flush(lines, line);
            }
            line.add(control);
        }
        flush(lines, line);
        return List.copyOf(lines);
    }

    /** Whether a control may share its line. The kinds whose control is a chip, a switch or a stepper. */
    private static boolean narrow(Kind kind) {
        return switch (kind) {
            case FLAG, CHOICE, INT, DECIMAL -> true;
            case ITEM, TEXT, TABLE_OPEN, RAW -> false;
        };
    }

    private static void flush(List<Line> lines, List<Control> line) {
        if (!line.isEmpty()) {
            lines.add(new Line(List.copyOf(line)));
            line.clear();
        }
    }
}
