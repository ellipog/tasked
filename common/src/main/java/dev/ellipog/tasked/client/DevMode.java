package dev.ellipog.tasked.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.ellipog.armature.api.ArmatureApi;
import dev.ellipog.tasked.Constants;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Whether this client is in developer mode: the switch that lets a screen carry controls which are tools
 * rather than content.
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
 */
public final class DevMode {

    /** The client preference this mod writes. Named for the side it is read on. */
    public static final String FILE_NAME = "client.json";

    private static boolean on;
    private static boolean snap = true;
    private static boolean progress = true;
    private static Path file;

    /**
     * What one file says, as two flags.
     *
     * <p>A record rather than two parses of one file: {@code load} reads once and takes both answers
     * from the same tree, so the two fields cannot disagree about what was on disk.
     *
     * @param dev  whether tools may be drawn
     * @param snap whether a dragged node lands on the grid; a file that does not say says yes
     * @param progress whether chapter rows draw their completion bar; likewise yes by default
     */
    public record Parsed(boolean dev, boolean snap, boolean progress) {
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

        if (Files.isRegularFile(path)) {
            try {
                Parsed read = parse(Files.readString(path, StandardCharsets.UTF_8));
                on = read.dev();
                snap = read.snap();
                progress = read.progress();
            }
            catch (IOException | RuntimeException e) {
                Constants.LOG.warn("tasked: {} could not be read, so developer mode is off. Deleting the"
                        + " file will stop this message.", path, e);
            }
        }
    }

    /**
     * Parses the file's text into both flags.
     *
     * <p>Tolerant rather than strict, and for a stronger reason than Appearance's: this file is a
     * developer's, so it will be hand-edited, and a typo in it should cost a mode that stays off rather
     * than a client that will not start. An unknown field is ignored; a missing one takes its default —
     * which for {@code snap} and {@code progress} is on, so a file written before either existed reads
     * as the behaviour it was already getting.
     *
     * @throws com.google.gson.JsonSyntaxException if the text is not JSON at all; {@link #load} catches it
     */
    public static Parsed parse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        return new Parsed(
                root.has("dev") && root.get("dev").getAsBoolean(),
                !root.has("snap") || root.get("snap").getAsBoolean(),
                !root.has("progress") || root.get("progress").getAsBoolean());
    }

    /** Whether the mode flag alone is set. See {@link #parse} for both. */
    public static boolean read(String json) {
        return parse(json).dev();
    }

    /** Writes the file. Answer given rather than the default so a test can assert the format. */
    public static String write(boolean dev, boolean snap, boolean progress) {
        JsonObject root = new JsonObject();
        root.addProperty("dev", dev);
        root.addProperty("snap", snap);
        root.addProperty("progress", progress);
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
        file = null;
    }

    private static void save() {
        if (file == null) {
            // No path means no platform: in a test, or in a client whose config directory could not be
            // resolved. The mode still works for this session, which is the useful half.
            return;
        }
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, write(on, snap, progress), StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            Constants.LOG.warn("tasked: developer mode could not be written to {}", file, e);
        }
    }
}
