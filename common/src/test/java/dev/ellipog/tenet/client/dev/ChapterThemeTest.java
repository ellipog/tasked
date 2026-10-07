package dev.ellipog.tenet.client.dev;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.ellipog.armature.client.ui.Theme;
import dev.ellipog.armature.client.ui.Themes;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A chapter's palette: the named theme, the patch over it, and the fallback when neither resolves.
 *
 * <h2>Why this is asserted rather than looked at</h2>
 *
 * <p>This is where "a chapter defines radius 4 and a raised surface" becomes a theme, and every way it
 * can be wrong looks like something else: a patch that silently does not apply reads as the theme file
 * being ignored, and a fallback that does not happen reads as the chapter having no opinion. The
 * composition is arithmetic on records, so it is pinned here.
 */
@DisplayName("ChapterTheme")
class ChapterThemeTest {

    private static JsonObject patch(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    @DisplayName("nothing said is the fallback, unchanged")
    void nothingSaidIsTheFallback() {
        Theme fallback = Themes.MONOCHROME;

        assertEquals(fallback, ChapterTheme.compose(null, null, fallback));
        assertEquals(fallback, ChapterTheme.compose("", new JsonObject(), fallback));
        assertEquals(fallback, ChapterTheme.compose("   ", null, fallback), "blank is not a name");
    }

    @Test
    @DisplayName("a name this build has is the palette; one it does not falls back")
    void namesResolveOrFallBack() {
        assertEquals(Themes.TOME, ChapterTheme.compose("tome", null, Themes.MONOCHROME));
        assertEquals(Themes.MONOCHROME,
                ChapterTheme.compose("no_such_theme", null, Themes.MONOCHROME));

        assertTrue(ChapterTheme.known("tome"));
        assertFalse(ChapterTheme.known("no_such_theme"));
        assertFalse(ChapterTheme.known(null), "no name is not a known name");
    }

    @Test
    @DisplayName("the patch overrides the named theme's colours and radius, and leaves the rest")
    void thePatchComposesOverTheName() {
        Theme composed = ChapterTheme.compose("tome", patch("""
                {"colours": {"raised": "#FF24242E"}, "cornerRadius": 4}"""), Themes.MONOCHROME);

        assertEquals(0xFF24242E, composed.raised(), "the patched token");
        assertEquals(4, composed.cornerRadius(), "the patched radius");
        assertEquals(Themes.TOME.panel(), composed.panel(), "an unpatched token is the named theme's");
        assertEquals(Themes.TOME.name(), composed.name(), "and the name still says which theme it was");
    }

    @Test
    @DisplayName("a patch applies over the player's theme when the chapter names none")
    void thePatchAppliesWithoutAName() {
        // A chapter that sets two colours without naming a theme is not a mistake: it wants the player's
        // palette with its own surfaces, and falling back entirely would ignore what it did say.
        Theme composed = ChapterTheme.compose(null, patch("""
                {"colours": {"tooltipText": "#FF112233"}}"""), Themes.MONOCHROME);

        assertEquals(0xFF112233, composed.tooltipText());
        assertEquals(Themes.MONOCHROME.panel(), composed.panel(), "the fallback underneath");
    }

    @Test
    @DisplayName("the chapter's patch wins over the book's own edits, not the other way round")
    void theChaptersPatchIsFinal() {
        // The rule this pins: the book's look is the *default* a chapter speaks over. A chapter that
        // patches a token has decided that surface for its own content, so the book's edit underneath
        // it does not come back on top -- where the chapter says nothing, the book's value shows.
        JsonObject chapterPatch = patch("""
                {"colours": {"line": "#FF6FA8DC"}}""");
        // What `Look.main()` looks like for an operator who edited the line colour in the book tab.
        Theme bookLook = Themes.PAPER.with("line", 0xFFA2967C);

        Theme composed = ChapterTheme.compose(null, chapterPatch, bookLook);

        assertEquals(0xFF6FA8DC, composed.line(), "the chapter's value is the one the content draws");
        assertEquals(Themes.PAPER.panel(), composed.panel(),
                "and a token neither names keeps the book's own");
    }

    @Test
    @DisplayName("a patch that names nothing this build knows changes nothing")
    void unreadablePatchesAreLenient() {
        // Read leniently on purpose: the validator is the side that refuses a file, and this side must
        // never throw while a player waits for a screen. Both entries here are unreadable, so the named
        // theme stands exactly as it was.
        Theme composed = ChapterTheme.compose("tome", patch("""
                {"colours": {"no_such_token": "#FF112233", "raised": "not a colour"}}"""),
                Themes.MONOCHROME);

        assertEquals(Themes.TOME, composed);
    }
}
