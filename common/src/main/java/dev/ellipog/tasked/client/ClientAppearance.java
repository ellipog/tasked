package dev.ellipog.tasked.client;

import dev.ellipog.armature.api.ArmatureApi;
import dev.ellipog.armature.client.Appearance;
import dev.ellipog.tasked.Constants;

import java.nio.file.Path;

/**
 * Tasked's own appearance: where its settings file and its themes live.
 *
 * <h2>Why this exists rather than a call into the library</h2>
 *
 * <p>Because the paths are the mod's. Armature used to resolve them itself -- a settings file and a theme
 * directory under {@code config/armature/} -- which meant any two mods built on it shared one player's one
 * look, so a quest book in Tome and a panel in Modern was not expressible. A library owns things and no
 * choices; this is Tasked's choice, in Tasked's directory, and Armature is handed the paths.
 *
 * <p>It is the same shape as {@link DevMode} deliberately: two settings, one file each, both under
 * {@code config/tasked/}, both read by the same two lines of each loader's client initialiser.
 */
public final class ClientAppearance {

    /** The settings file, beside {@link DevMode}'s. */
    public static final String FILE_NAME = "appearance.json";

    /** Where a theme saved from the editor is written. */
    public static final String THEMES_DIRECTORY = "themes";

    private ClientAppearance() {
    }

    /**
     * Reads Tasked's appearance, and tells Armature where to write a theme it saves.
     *
     * <p>Called once per client, from the loader's client initialiser, for the reason {@code DevMode}'s
     * note gives: a setting read late is a setting that is wrong for the first second of a session.
     */
    public static void loadFromConfig() {
        Path config;
        try {
            config = ArmatureApi.platform().configDir(Constants.MOD_ID);
        }
        catch (RuntimeException e) {
            Constants.LOG.warn("tasked: the platform layer was not ready, so the appearance was not read."
                    + " The default theme is in use.", e);
            return;
        }
        Appearance.load(config.resolve(FILE_NAME), config.resolve(THEMES_DIRECTORY));
    }
}
