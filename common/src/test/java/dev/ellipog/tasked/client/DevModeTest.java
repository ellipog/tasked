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
        assertNull(DevMode.file(), "and no file has been read yet");
    }

    @Test
    @DisplayName("reading, writing and reading back all three flags")
    void roundTrip(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(DevMode.FILE_NAME);

        assertEquals("{\"dev\":true,\"snap\":true,\"progress\":true}", DevMode.write(true, true, true),
                "the format, stated once");
        assertEquals("{\"dev\":false,\"snap\":false,\"progress\":false}", DevMode.write(false, false, false));

        Files.writeString(file, DevMode.write(true, true, true), StandardCharsets.UTF_8);
        DevMode.load(file);
        assertEquals(file, DevMode.file(), "the file it read is the file it will write");
        assertTrue(DevMode.on());
        assertTrue(DevMode.snap());
        assertTrue(DevMode.progress());

        // And the other direction, which is the one a toggle takes.
        DevMode.setOn(false);
        DevMode.setSnap(false);
        DevMode.setProgress(false);
        assertFalse(DevMode.on());
        assertFalse(DevMode.snap());
        assertFalse(DevMode.progress());
        String written = Files.readString(file, StandardCharsets.UTF_8);
        assertFalse(DevMode.read(written), "what was written is what is read");
        DevMode.load(file);
        assertFalse(DevMode.snap(), "and the snap flag round-trips through the same file");
        assertFalse(DevMode.progress(), "and so does the bars' switch");
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
