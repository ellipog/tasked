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
 * is. The flag defaults to off, because a mode that changes what a screen shows should never be on
 * because a file was unreadable.
 */
public final class DevMode {

    /** The client preference this mod writes. Named for the side it is read on. */
    public static final String FILE_NAME = "client.json";

    private static boolean on;
    private static Path file;

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

        if (Files.isRegularFile(path)) {
            try {
                on = read(Files.readString(path, StandardCharsets.UTF_8));
            }
            catch (IOException | RuntimeException e) {
                Constants.LOG.warn("tasked: {} could not be read, so developer mode is off. Deleting the"
                        + " file will stop this message.", path, e);
            }
        }
    }

    /**
     * Parses the file's text.
     *
     * <p>Tolerant rather than strict, and for a stronger reason than Appearance's: this file is a
     * developer's, so it will be hand-edited, and a typo in it should cost a mode that stays off rather
     * than a client that will not start. An unknown field is ignored; a missing one takes its default.
     *
     * @throws com.google.gson.JsonSyntaxException if the text is not JSON at all; {@link #load} catches it
     */
    public static boolean read(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        return root.has("dev") && root.get("dev").getAsBoolean();
    }

    /** Writes the setting. Answer given rather than the default so a test can assert the format. */
    public static String write(boolean value) {
        JsonObject root = new JsonObject();
        root.addProperty("dev", value);
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

    /** Forgets the setting and the file. For a test, and for a client leaving a world it never owned. */
    public static void reset() {
        on = false;
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
            Files.writeString(file, write(on), StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            Constants.LOG.warn("tasked: developer mode could not be written to {}", file, e);
        }
    }
}
