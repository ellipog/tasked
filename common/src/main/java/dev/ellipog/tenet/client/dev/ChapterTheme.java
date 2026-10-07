package dev.ellipog.tenet.client.dev;

import com.google.gson.JsonObject;

import dev.ellipog.armature.client.ui.Theme;
import dev.ellipog.armature.client.ui.ThemePatch;
import dev.ellipog.armature.client.ui.Themes;

import java.util.ArrayList;
import java.util.List;

/**
 * A chapter's palette: the theme it names (or the player's own), with its token patch composed over it.
 *
 * <h2>One place a palette is assembled</h2>
 *
 * <p>Main → chapter is the order today, and a quest-level patch would slot between them here rather than
 * at a call site: nothing else in the screen knows how a palette is built, which is what stops two
 * drawing paths from disagreeing about what a chapter looks like.
 *
 * <h2>Composed per call rather than cached</h2>
 *
 * <p>It is a handful of calls per frame — the canvas, the card, the tooltips — and the composition is
 * one record copy. A cache would have to key on the fallback as well as on the patch, and the player can
 * edit the fallback under an unchanged name, so the key would lie the moment they did. The same
 * argument {@code ClientAppearance} makes about not caching its own theme.
 */
public final class ChapterTheme {

    private ChapterTheme() {
    }

    /**
     * The palette in force: {@code named} resolved against the catalogue, or {@code fallback} when it
     * names nothing this build knows; then {@code patch} applied over it.
     *
     * <p>A patch is applied even when the name resolved to nothing: a chapter that misspells its theme
     * and sets two colours should still get those two colours. The alternative is a chapter plainly
     * trying to be themed rendering as if it had said nothing at all, which reads as the feature being
     * broken rather than the name being wrong.
     *
     * <p>A malformed patch is read leniently by the toolkit's own parser — bad tokens and unreadable
     * values are dropped, and the rest still applies. The validator reports them on the side that can
     * refuse the file; this side must never throw while a player waits for a screen.
     * <h2>The chapter's choice is final</h2>
     *
     * <p>Whatever this returns is what the chapter's content draws: nothing re-applies the book's own
     * edits over it. A chapter that names a theme or patches a token has spoken about that surface,
     * and the book's look is the default it speaks over — not a layer on top. Where the chapter says
     * nothing, the fallback (the book's look, the player's own edits included) shows through unchanged.
     */
    public static Theme compose(String named, JsonObject patch, Theme fallback) {
        Theme base = named == null || named.isBlank() ? fallback : Themes.any(named);
        if (base == null) {
            base = fallback;
        }
        if (patch == null || patch.size() == 0) {
            return base;
        }
        List<String> problems = new ArrayList<>();
        ThemePatch parsed = ThemePatch.fromJson(patch, problems);
        if (parsed.isEmpty()) {
            return base;
        }
        return parsed.applyTo(base);
    }

    /** Whether a name resolves to a theme this build has, for the caller's once-per-chapter warning. */
    public static boolean known(String named) {
        return named != null && !named.isBlank() && Themes.any(named) != null;
    }
}
