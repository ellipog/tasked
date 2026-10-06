package dev.ellipog.tasked.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Developer mode: the flag, its file, and what a bad file does.
 *
 * <h2>Why the file is worth this many tests</h2>
 *
 * <p>Because it is a switch that changes what a screen shows, and the two ways it can be wrong are both
 * silent: a typo could leave it stuck on (a book with tools nobody asked for), or a file that cannot be
 * read could leave it stuck off with no way to tell -- and neither prints anything on screen. The
 * tolerance is therefore asserted rather than assumed: no file, empty file, not JSON, wrong type and an
 * unknown field all end at "off, and the client still runs", which is the direction that cannot lie
 * about what is on screen.
 *
 * <p>It holds three settings that are <b>not</b> gated on the mode -- snap, the sidebar's bars, and the
 * side panels -- and that is asserted here too, because a preference that silently required developer
 * mode would be a switch nobody could find the effect of.
 */
@DisplayName("Developer mode")
class DevModeTest {

    @AfterEach
    void forget() {
        // A static flag with a static path: a test that leaves either set changes the next one.
        DevMode.reset();
    }

    @Test
    @DisplayName("off, unless a file says otherwise")
    void offByDefault() {
        assertFalse(DevMode.on());
        assertTrue(DevMode.snap(), "the grid is the default, and is not gated on the mode");
        assertTrue(DevMode.progress(), "and so are the sidebar's chapter progress bars");
        assertTrue(DevMode.panels(), "the docked column is the default: every kind has been converted");
        assertEquals(PanelStack.WIDTH, DevMode.panelWidth(), "and the column opens at its ordinary width");
        assertEquals(PanelStack.Fold.AUTO, DevMode.panelFold(), "with the window deciding about folding");
        assertNull(DevMode.file(), "and no file has been read yet");
    }

    @Test
    @DisplayName("reading, writing and reading back every flag")
    void roundTrip(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(DevMode.FILE_NAME);

        assertEquals("{\"dev\":true,\"snap\":true,\"progress\":true,\"panels\":true,\"panelWidth\":340,"
                        + "\"panelFold\":\"auto\"}",
                DevMode.write(true, true, true, true, PanelStack.WIDTH, PanelStack.Fold.AUTO),
                "the format, stated once");
        assertEquals("{\"dev\":false,\"snap\":false,\"progress\":false,\"panels\":false,\"panelWidth\":240,"
                        + "\"panelFold\":\"off\"}",
                DevMode.write(false, false, false, false, PanelStack.MIN_WIDTH, PanelStack.Fold.NEVER));

        Files.writeString(file,
                DevMode.write(true, true, true, true, PanelStack.WIDTH, PanelStack.Fold.AUTO),
                StandardCharsets.UTF_8);
        DevMode.load(file);
        assertEquals(file, DevMode.file(), "the file it read is the file it will write");
        assertTrue(DevMode.on());
        assertTrue(DevMode.snap());
        assertTrue(DevMode.progress());
        assertTrue(DevMode.panels());
        assertEquals(PanelStack.WIDTH, DevMode.panelWidth());
        assertEquals(PanelStack.Fold.AUTO, DevMode.panelFold());

        // And the other direction, which is the one a toggle takes.
        DevMode.setOn(false);
        DevMode.setSnap(false);
        DevMode.setProgress(false);
        DevMode.setPanels(false);
        DevMode.setPanelWidth(PanelStack.SECOND_WIDTH);
        DevMode.setPanelFold(PanelStack.Fold.ALWAYS);
        assertFalse(DevMode.on());
        assertFalse(DevMode.snap());
        assertFalse(DevMode.progress());
        assertFalse(DevMode.panels());
        String written = Files.readString(file, StandardCharsets.UTF_8);
        assertFalse(DevMode.read(written), "what was written is what is read");
        DevMode.load(file);
        assertFalse(DevMode.snap(), "and the snap flag round-trips through the same file");
        assertFalse(DevMode.progress(), "and so does the bars' switch");
        assertFalse(DevMode.panels(), "and so does the layout's");
        assertEquals(PanelStack.SECOND_WIDTH, DevMode.panelWidth(), "and the width");
        assertEquals(PanelStack.Fold.ALWAYS, DevMode.panelFold(), "and which way the fold was asked for");
    }

    @Test
    @DisplayName("the layout's switch toggles on its own, without developer mode")
    void theLayoutSwitchIsItsOwn(@TempDir Path dir) throws IOException {
        // The rule snap and the bars already follow: a player who never opens developer mode still has a
        // layout, and it is theirs rather than the pack's.
        assertFalse(DevMode.on());
        // The default is the docked column now, so this asserts the property rather than a starting state:
        // the toggle flips whatever is there, and answers with what it left behind.
        boolean before = DevMode.panels();
        assertEquals(!before, DevMode.togglePanels(), "the toggle answers with the state it left behind");
        assertEquals(!before, DevMode.panels());
        assertFalse(DevMode.on(), "and it did not turn developer mode on to do it");
        assertEquals(before, DevMode.togglePanels());
        assertEquals(before, DevMode.panels());

        // A width outside every range is clamped on the way in rather than stored as nonsense.
        DevMode.setPanelWidth(-100);
        assertEquals(PanelStack.MIN_WIDTH, DevMode.panelWidth());
        DevMode.setPanelWidth(PanelStack.WIDE_WIDTH + 5_000);
        assertEquals(PanelStack.WIDE_WIDTH, DevMode.panelWidth(),
                "the stored width may be a wide kind's, so it is not clamped to a prose column");
    }

    @Test
    @DisplayName("a file from before the grid existed reads as snapping on")
    void oldFileStillSnaps(@TempDir Path dir) throws IOException {
        Path old = dir.resolve(DevMode.FILE_NAME);
        Files.writeString(old, "{\"dev\":true}", StandardCharsets.UTF_8);
        DevMode.load(old);
        assertTrue(DevMode.on());
        assertTrue(DevMode.snap(), "a missing field takes its default, which is on for the grid");
        assertTrue(DevMode.progress(), "and for the progress bars");
        assertTrue(DevMode.panels(), "and a file written before the key existed gets the default, which is the column now");
        assertEquals(PanelStack.WIDTH, DevMode.panelWidth(), "at the ordinary width");
        assertEquals(PanelStack.Fold.AUTO, DevMode.panelFold(), "with the window deciding about folding");
    }

    @Test
    @DisplayName("a width that is not a number discards the file, like any other unreadable one")
    void aWrongTypedWidthIsABadFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(DevMode.FILE_NAME);
        Files.writeString(file, "{\"dev\":true,\"panels\":true,\"panelWidth\":\"wide\"}",
                StandardCharsets.UTF_8);
        DevMode.load(file);

        // The whole file goes, which is the rule every other wrong type already follows -- and it is the
        // safe direction: a client that cannot read the file draws what it shipped with.
        assertFalse(DevMode.on());
        assertTrue(DevMode.panels(), "a refused file leaves the defaults, and the default is the column");
        assertEquals(PanelStack.WIDTH, DevMode.panelWidth());
    }

    @Test
    @DisplayName("a fold word nobody knows means the window decides, rather than a discarded file")
    void anUnknownFoldWordIsAuto(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(DevMode.FILE_NAME);
        Files.writeString(file, "{\"dev\":true,\"panels\":true,\"panelFold\":\"sometimes\"}",
                StandardCharsets.UTF_8);
        DevMode.load(file);

        // Unlike a wrong *type*, an unknown word is a preference this build does not have a name for, and
        // the rest of the file is perfectly readable -- so only that one setting falls back.
        assertTrue(DevMode.on(), "the file was read, because nothing in it was the wrong shape");
        assertTrue(DevMode.panels());
        assertEquals(PanelStack.Fold.AUTO, DevMode.panelFold());
    }

    @Test
    @DisplayName("a file that is missing, empty, wrong or unreadable leaves it off")
    void aBadFileIsOff(@TempDir Path dir) throws IOException {
        Path missing = dir.resolve("not-there.json");
        DevMode.load(missing);
        assertFalse(DevMode.on(), "a first run is off, and says nothing");

        Path empty = dir.resolve("empty.json");
        Files.writeString(empty, "", StandardCharsets.UTF_8);
        DevMode.load(empty);
        assertFalse(DevMode.on(), "an empty file is not JSON, and is not a crash");

        Path wrongType = dir.resolve("wrong.json");
        Files.writeString(wrongType, "{\"dev\": \"yes please\"}", StandardCharsets.UTF_8);
        DevMode.load(wrongType);
        assertFalse(DevMode.on(), "a string where a boolean belongs");

        Path unknown = dir.resolve("unknown.json");
        Files.writeString(unknown, "{\"developer\": true}", StandardCharsets.UTF_8);
        DevMode.load(unknown);
        assertFalse(DevMode.on(), "a misspelled field is not the field");

        // Nothing above threw, which is half the assertion: this file is read on the client's way in,
        // and a mode that cannot be read is worth less than a client that cannot start.
        assertTrue(Files.exists(missing.getParent()), "the directory was not consumed by any of them");
    }

    @Test
    @DisplayName("without a file the mode still toggles, for this session only")
    void noFileStillWorks() {
        // The state a test runs in, and the state a client runs in if the platform could not resolve a
        // config directory: the switch works and simply forgets.
        assertTrue(DevMode.toggle());
        assertTrue(DevMode.on());
        assertFalse(DevMode.toggle());
        assertFalse(DevMode.on());
        assertNull(DevMode.file());
    }
}
