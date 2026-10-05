package dev.ellipog.tasked.quest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The naming rules, which are the loader's rules plus the filesystem's.
 *
 * <p>The underscore case is the one worth the test: it is a string {@code Checks.id} accepts and every
 * folder walk skips, so a card that allowed it would create content that loads as nothing and cannot be
 * selected, renamed or deleted from the very list that made it.
 */
@DisplayName("ChapterNaming")
class ChapterNamingTest {

    private static final List<String> TAKEN = List.of("first_steps", "shape_gallery");

    @Test
    @DisplayName("an id the loader would read back is accepted")
    void acceptsAReadableId() {
        assertNull(ChapterNaming.problemWith("new_chapter_2", TAKEN));
    }

    @Test
    @DisplayName("a leading underscore is refused, because the walk skips it")
    void refusesALeadingUnderscore() {
        assertNotNull(ChapterNaming.problemWith("_notes", TAKEN));
    }

    @Test
    @DisplayName("the loader's other limits are the same ones here")
    void refusesWhatTheLoaderRefuses() {
        assertNotNull(ChapterNaming.problemWith("", TAKEN), "empty");
        assertNotNull(ChapterNaming.problemWith("Upper", TAKEN), "capitals");
        assertNotNull(ChapterNaming.problemWith("with-dash", TAKEN), "a dash");
        assertNotNull(ChapterNaming.problemWith("a".repeat(65), TAKEN), "too long");
        assertNotNull(ChapterNaming.problemWith("gone.deleted", TAKEN), "the deleted suffix");
    }

    @Test
    @DisplayName("an id already in use is refused, and says so")
    void refusesACollision() {
        String problem = ChapterNaming.problemWith("first_steps", TAKEN);
        assertNotNull(problem);
        assertEquals(true, problem.contains("already used"));
    }

    @Test
    @DisplayName("a suggestion is the first free name, which is what a duplicate opens on")
    void suggestsAFreeName() {
        assertEquals("one_copy", ChapterNaming.suggested("one", "_copy", List.of()));
        assertEquals("one_copy2", ChapterNaming.suggested("one", "_copy", List.of("one_copy")));
        assertEquals("one_copy3", ChapterNaming.suggested("one", "_copy",
                List.of("one_copy", "one_copy2")));
    }
}
