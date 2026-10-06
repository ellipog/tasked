package dev.ellipog.tasked.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.ellipog.armature.api.ArmatureApi;
import dev.ellipog.armature.api.data.JsonWrite;
import dev.ellipog.tasked.Constants;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * This client's own preferences: whether tools may be drawn, and how the book prefers to be shaped.
 *
 * <h2>Why a mode rather than a set of buttons</h2>
 *
 * <p>Because the quest book is content, and a theme picker is a tool. The two rows that used to sit at
 * the foot of the book's sidebar were removed in the theme round for exactly that reason, and this is
 * where they went: a pack author needs a mode, not a permanent set of controls on somebody else's
 * screen. FTB Quests has the same shape for the same reason.
 *
 * <h2>Why the flag is Tasked's rather than Armature's</h2>
 *
 * <p>Because what it gates is this mod's screens. Armature owns the things a tool *operates* -- the
 * appearance, the themes, the token registry, the file format -- and every one of those already exists
 * and is asserted. What did not exist was a place to put the controls, and the editor for quests that
 * will follow them is Tasked's, so the mode is Tasked's too. The argument that moved the picker *out*
 * of Armature's toolbox and into a setting applies in reverse here: a control wrapped around an
 * operation that already lives in the library can be moved without anything being rewritten.
 *
 * <h2>The file, and what happens when it is wrong</h2>
 *
 * <p>{@code config/tasked/client.json}, a client preference beside Armature's own
 * {@code appearance.json}, and the same tolerance in the same places: a missing file is a first run and
 * is worth no words at all, and a file that exists and cannot be read is worth one that says where it
 * is. The mode flag defaults to off, because a mode that changes what a screen shows should never be on
 * because a file was unreadable. Beside it ride two on-by-default switches: the editor's snap, which
 * changes where a dragged node lands, and the sidebar's chapter progress bars, which change what a
 * chapter row shows. Both are the tidy direction, so a file that does not name them leaves them on.
 *
 * <h2>Three later settings, and why they are here rather than in a file of their own</h2>
 *
 * <p>The side panels -- whether they are used at all, how wide the column is, and whether the second
 * column folds -- are the same kind of thing the two switches above are: a preference about how this
 * client draws, read before anything can draw it. <b>None of the three is gated on {@link #on()}</b>,
 * which is the rule {@code snap} and {@code progress} already follow and which is worth keeping: a
 * player who never turns developer mode on still has a layout, and it is theirs rather than the pack's.
 * The layout flag defaults <b>on</b>: the panels round converted every kind, so the docked column is
 * what a player gets, and Ctrl+P -- or a false in this file -- is how they ask for the centred card
 * instead. It was off only while the conversion was incomplete, which is the only reason it was ever off.
 */
public final class DevMode {

    /** The client preference this mod writes. Named for the side it is read on. */
    public static final String FILE_NAME = "client.json";

    private static boolean on;
    private static boolean snap = true;
    private static boolean progress = true;
    private static boolean panels = true;
    private static int panelWidth = PanelStack.WIDTH;
    private static PanelStack.Fold panelFold = PanelStack.Fold.AUTO;
    private static Path file;

    /**
     * What one file says, as every flag it holds.
     *
     * <p>A record rather than several parses of one file: {@code load} reads once and takes every answer
     * from the same tree, so the fields cannot disagree about what was on disk.
     *
     * @param dev  whether tools may be drawn
     * @param snap whether a dragged node lands on the grid; a file that does not say says yes
     * @param progress whether chapter rows draw their completion bar; likewise yes by default
     * @param panels whether an overlay is docked in a side column rather than centred as a card; yes by
     *               default, since that is the presentation every kind has been converted to
     * @param panelWidth how wide that column is, in GUI pixels; clamped on the way in
     * @param panelFold what the player asked the second column to do
     */
    public record Parsed(boolean dev, boolean snap, boolean progress, boolean panels, int panelWidth,
            PanelStack.Fold panelFold) {
    }

    private DevMode() {
    }

    /** Whether tools may be drawn on this client's screens. Off unless a file said otherwise. */
    public static boolean on() {
        return on;
    }

    /** Turns the mode on or off and writes the choice. */
    public static void setOn(boolean next) {
        on = next;
        save();
    }

    /** The same, and answers with the state it left behind, so a button can label itself from one call. */
    public static boolean toggle() {
        setOn(!on);
        return on;
    }

    // ------------------------------------------------------------------
    // The editor's grid switch
    // ------------------------------------------------------------------

    /**
     * Whether the editor snaps a moved node to {@link BookGeometry#SNAP_GRID}. On by default, because
     * the grid is what keeps a dragged position a number an author would have typed; Alt asks for the
     * one free placement and does not need a setting flipped to get it.
     */
    public static boolean snap() {
        return snap;
    }

    /** Turns the grid on or off and writes the choice. */
    public static void setSnap(boolean next) {
        snap = next;
        save();
    }

    // ------------------------------------------------------------------
    // The sidebar's progress bars
    // ------------------------------------------------------------------

    /**
     * Whether chapter rows draw their completion bar. On by default: the bar is the sidebar's quiet
     * answer to "how far through is this chapter", and a preference that defaulted off would hide a
     * feature behind a switch nobody knows to look for.
     */
    public static boolean progress() {
        return progress;
    }

    /** Turns the bars on or off and writes the choice. */
    public static void setProgress(boolean next) {
        progress = next;
        save();
    }

    // ------------------------------------------------------------------
    // The side panels
    // ------------------------------------------------------------------

    /**
     * Whether an overlay is drawn in the docked side column rather than as a centred card.
     *
     * <p><b>Off</b> unless a file says otherwise: the card is the presentation this mod shipped with, and
     * a second one is a choice rather than an upgrade that arrives uninvited.
     */
    public static boolean panels() {
        return panels;
    }

    /** Turns the docked presentation on or off and writes the choice. */
    public static void setPanels(boolean next) {
        panels = next;
        save();
    }

    /** The same, answering with the state it left behind, so a switch can label itself from one call. */
    public static boolean togglePanels() {
        setPanels(!panels);
        return panels;
    }

    /**
     * How wide the column is, in GUI pixels.
     *
     * <p>Clamped to what any panel may legally be ({@link PanelStack#clampStoredWidth}) rather than to
     * what one kind wants, because this is the player's remembered width and may have been chosen for a
     * rewards inbox. What a <i>particular</i> kind is allowed is the drag's question, and it asks
     * {@link PanelStack#clampWidth}.
     */
    public static int panelWidth() {
        return panelWidth;
    }

    /** Remembers a width and writes it, clamped to something a panel can be. */
    public static void setPanelWidth(int next) {
        panelWidth = PanelStack.clampStoredWidth(next);
        save();
    }

    /** What the player asked the second column to do. */
    public static PanelStack.Fold panelFold() {
        return panelFold;
    }

    /** Remembers that choice and writes it. */
    public static void setPanelFold(PanelStack.Fold next) {
        panelFold = next == null ? PanelStack.Fold.AUTO : next;
        save();
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    /** Reads the setting from the platform's config directory. Called once by each loader's client. */
    public static void loadFromConfig() {
        Path path = null;
        try {
            path = ArmatureApi.platform().configDir(Constants.MOD_ID).resolve(FILE_NAME);
        }
        catch (RuntimeException e) {
            // Reachable only if this runs before the platform is installed, which would be a caller
            // ordering fault rather than a player's. Off is the working default either way.
            Constants.LOG.warn("tasked: the platform layer was not ready, so developer mode was not read."
                    + " It is off.", e);
        }

        if (path == null) {
            return;
        }
        load(path);
    }

    /**
     * Reads the setting from one file.
     *
     * <p>A missing file is a first run and gets no message; a file that is there and cannot be read gets
     * one that names it. Neither stops the client, and neither turns the mode on.
     */
    public static void load(Path path) {
        file = path;
        on = false;
        snap = true;
        progress = true;
        panels = true;
        panelWidth = PanelStack.WIDTH;
        panelFold = PanelStack.Fold.AUTO;

        if (Files.isRegularFile(path)) {
            try {
                Parsed read = parse(Files.readString(path, StandardCharsets.UTF_8));
                on = read.dev();
                snap = read.snap();
                progress = read.progress();
                panels = read.panels();
                panelWidth = PanelStack.clampStoredWidth(read.panelWidth());
                panelFold = read.panelFold() == null ? PanelStack.Fold.AUTO : read.panelFold();
            }
            catch (IOException | RuntimeException e) {
                Constants.LOG.warn("tasked: {} could not be read, so developer mode is off. Deleting the"
                        + " file will stop this message.", path, e);
            }
        }
    }

    /**
     * Parses the file's text into every flag.
     *
     * <p>Tolerant rather than strict, and for a stronger reason than Appearance's: this file is a
     * developer's, so it will be hand-edited, and a typo in it should cost a mode that stays off rather
     * than a client that will not start. An unknown field is ignored; a missing one takes its default —
     * which for {@code snap} and {@code progress} is on, so a file written before either existed reads
     * as the behaviour it was already getting, and which for the three panel settings is the presentation
     * this mod shipped with.
     *
     * @throws com.google.gson.JsonSyntaxException if the text is not JSON at all; {@link #load} catches it
     */
    public static Parsed parse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        return new Parsed(
                root.has("dev") && root.get("dev").getAsBoolean(),
                !root.has("snap") || root.get("snap").getAsBoolean(),
                !root.has("progress") || root.get("progress").getAsBoolean(),
                !root.has("panels") || root.get("panels").getAsBoolean(),
                root.has("panelWidth") ? root.get("panelWidth").getAsInt() : PanelStack.WIDTH,
                root.has("panelFold")
                        ? PanelStack.foldOf(root.get("panelFold").getAsString())
                        : PanelStack.Fold.AUTO);
    }

    /** Whether the mode flag alone is set. See {@link #parse} for every field. */
    public static boolean read(String json) {
        return parse(json).dev();
    }

    /** Writes the file. Answer given rather than the default so a test can assert the format. */
    public static String write(boolean dev, boolean snap, boolean progress, boolean panels, int panelWidth,
            PanelStack.Fold fold) {
        JsonObject root = new JsonObject();
        root.addProperty("dev", dev);
        root.addProperty("snap", snap);
        root.addProperty("progress", progress);
        root.addProperty("panels", panels);
        root.addProperty("panelWidth", panelWidth);
        root.addProperty("panelFold", PanelStack.foldWord(fold));
        return root.toString();
    }

    /**
     * The file this client reads and writes, or null before {@link #loadFromConfig} has run.
     *
     * <p>Exposed because a setting with no way to ask where it lives is a setting that is hard to
     * believe: the screen that changes it and the file a person edits have to be the same file.
     */
    public static Path file() {
        return file;
    }

    /** Forgets the settings and the file. For a test, and for a client leaving a world it never owned. */
    public static void reset() {
        on = false;
        snap = true;
        progress = true;
        panels = true;
        panelWidth = PanelStack.WIDTH;
        panelFold = PanelStack.Fold.AUTO;
        file = null;
    }

    private static void save() {
        if (file == null) {
            // No path means no platform: in a test, or in a client whose config directory could not be
            // resolved. The mode still works for this session, which is the useful half.
            return;
        }
        try {
            // Through JsonWrite, which creates the directory and writes by rename -- so a crash mid-save
            // leaves the previous flags rather than a half-written file the next load has to guess at.
            // This file's whole contract is that every way of being wrong reads as the safe direction,
            // and a truncated file is the one way that could not be honoured. See JsonWrite.
            JsonWrite.atomically(file, write(on, snap, progress, panels, panelWidth, panelFold));
        }
        catch (IOException e) {
            Constants.LOG.warn("tasked: developer mode could not be written to {}", file, e);
        }
    }
}
