package dev.ellipog.tasked.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The prose rule: blank paragraphs at the ends go, everything between stays.
 *
 * <p>The fixture that matters is the one from play: the description that reported "loads of empty space
 * at the bottom" ended in two empty paragraphs, and the one it was written with has an empty paragraph
 * in the middle that has to survive -- trimming that would join two paragraphs that were deliberately
 * separated.
 */
@DisplayName("a description's ends")
class ProseTest {

    @Test
    @DisplayName("blank paragraphs at the ends are dropped, and the blank ones between are kept")
    void theEndsAreTrimmedAndTheMiddleIsNot() {
        assertEquals(List.of("one", "", "two"),
                Prose.trimmed(List.of("", "one", "", "two", "", "")));
        assertEquals(List.of("one", "", "", "two"), Prose.trimmed(List.of("one", "", "", "two")),
                "a run of blanks in the middle is a paragraph break the author wrote twice");
        assertEquals(List.of("one"), Prose.trimmed(List.of("one")));
    }

    @Test
    @DisplayName("a whitespace-only paragraph is blank at an end, because it draws as an empty line")
    void spacesAreBlankToo() {
        assertEquals(List.of("one"), Prose.trimmed(List.of("  ", "one", " \t ")));
    }

    @Test
    @DisplayName("a description of nothing but blanks is nothing, which is the caller's empty state")
    void allBlankComesBackEmpty() {
        assertEquals(List.of(), Prose.trimmed(List.of()));
        assertEquals(List.of(), Prose.trimmed(List.of("", "   ", "")));
    }

    @Test
    @DisplayName("nothing to trim is the same list, not a copy of it")
    void nothingToTrimIsLeftAlone() {
        List<String> already = List.of("one", "", "two");
        assertSame(already, Prose.trimmed(already));
    }
}
