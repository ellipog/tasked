package dev.ellipog.tenet.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every token FTB packs actually carry, against the strings they came from.
 */
class FtbTextTest {

    private static List<FtbText.Segment> parse(String text) {
        return FtbText.parse(text);
    }

    private static FtbText.Text onlyText(String text) {
        List<FtbText.Segment> segments = parse(text);
        assertEquals(1, segments.size(), "one run: " + segments);
        return assertInstanceOf(FtbText.Text.class, segments.get(0));
    }

    @Test
    @DisplayName("plain prose is one white run, with nothing set")
    void plainProseIsOneRun() {
        FtbText.Text run = onlyText("Punch a Tree");
        assertEquals("Punch a Tree", run.text());
        assertEquals(FtbText.WHITE, run.argb());
        assertTrue(!run.bold() && !run.italic() && !run.underline() && !run.strike()
                && !run.obfuscated(), "no flags: " + run);
        assertTrue(run.link().isEmpty());
    }

    @Test
    @DisplayName("a converted chapter title reads in three runs")
    void convertedTitleReadsInRuns() {
        // The shape every ATM chapter title arrives in: `&aChapter 2&r: &6The ATM Star`.
        List<FtbText.Segment> segments = parse("&aChapter 2&r: &6The ATM Star");
        assertEquals(3, segments.size(), "green words, a white join, gold words: " + segments);

        FtbText.Text first = assertInstanceOf(FtbText.Text.class, segments.get(0));
        assertEquals("Chapter 2", first.text());
        assertEquals(0xFF55FF55, first.argb(), "FTB `&a` is vanilla green");

        FtbText.Text join = assertInstanceOf(FtbText.Text.class, segments.get(1));
        assertEquals(": ", join.text());
        assertEquals(FtbText.WHITE, join.argb(), "`&r` resets to white");

        FtbText.Text last = assertInstanceOf(FtbText.Text.class, segments.get(2));
        assertEquals("The ATM Star", last.text());
        assertEquals(0xFFFFAA00, last.argb(), "FTB `&6` is vanilla gold");
    }

    @Test
    @DisplayName("section signs read exactly like ampersands, in either case")
    void sectionSignsMatchAmpersands() {
        assertEquals(parse("&aGreen"), parse("§aGreen"));
        assertEquals(parse("&A Green"), parse("&a Green"));
        assertEquals(0xFFFF5555, onlyText("§cRed").argb(), "`§c` is vanilla red");
    }

    @Test
    @DisplayName("a colour keeps the flags, a style keeps the ink, and reset clears both")
    void coloursAndStylesCompose() {
        List<FtbText.Segment> segments = parse("&lBold &cBold Red&r Plain");
        assertEquals(3, segments.size(), segments.toString());

        FtbText.Text bold = assertInstanceOf(FtbText.Text.class, segments.get(0));
        assertTrue(bold.bold());
        assertEquals(FtbText.WHITE, bold.argb());

        FtbText.Text boldRed = assertInstanceOf(FtbText.Text.class, segments.get(1));
        assertTrue(boldRed.bold(), "a colour does not clear bold");
        assertEquals(0xFFFF5555, boldRed.argb());

        FtbText.Text plain = assertInstanceOf(FtbText.Text.class, segments.get(2));
        assertTrue(!plain.bold() && plain.argb() == FtbText.WHITE, "reset clears both: " + plain);
    }

    @Test
    @DisplayName("hex inks and underline/italic/strike arrive on their runs")
    void hexAndStyles() {
        FtbText.Text hex = onlyText("&#FF0000Red");
        assertEquals(0xFFFF0000, hex.argb());

        FtbText.Text styled = onlyText("&n&o&mAll Three");
        assertTrue(styled.underline() && styled.italic() && styled.strike(), styled.toString());
        assertTrue(!styled.bold() && !styled.obfuscated());
    }

    @Test
    @DisplayName("an escaped ampersand is one literal &, and a stray marker is words")
    void escapesAndStrays() {
        assertEquals("Forbidden & Arcanus", onlyText("Forbidden \\& Arcanus").text());
        assertEquals("100&", onlyText("100&").text(), "a trailing marker is literal");
        assertEquals("&x", onlyText("&x").text(), "a marker before no code is two characters");
        assertEquals("a & b", onlyText("a & b").text());
    }

    @Test
    @DisplayName("a pagebreak is a boundary, not words")
    void pagebreakIsABoundary() {
        List<FtbText.Segment> segments = parse("First page{@pagebreak}Second page");
        assertEquals(3, segments.size(), segments.toString());
        assertEquals("First page", assertInstanceOf(FtbText.Text.class, segments.get(0)).text());
        assertInstanceOf(FtbText.PageBreak.class, segments.get(1));
        assertEquals("Second page", assertInstanceOf(FtbText.Text.class, segments.get(2)).text());
    }

    @Test
    @DisplayName("an inline picture carries its source, size, alignment and spare pairs")
    void inlineImage() {
        // The shape converted packs actually carry.
        List<FtbText.Segment> segments = parse("Look:{image:atm:textures/questpics/pneumaticcraft/block_tracker.png"
                + " width:150 height:150 align:center}Done");
        assertEquals(3, segments.size(), segments.toString());

        FtbText.InlineImage image = assertInstanceOf(FtbText.InlineImage.class, segments.get(1));
        assertEquals("atm:textures/questpics/pneumaticcraft/block_tracker.png", image.src());
        assertEquals(150, image.width());
        assertEquals(150, image.height());
        assertEquals("center", image.align());

        FtbText.InlineImage sparse = assertInstanceOf(FtbText.InlineImage.class,
                parse("{image:pack:textures/x.png}").get(0));
        assertEquals(0, sparse.width(), "unnamed size is zero, for the consumer to fill");
        assertEquals("center", sparse.align());
    }

    @Test
    @DisplayName("a hand link captures its address, and a lookup captures its key")
    void openUrlAndSubstitute() {
        FtbText.OpenUrl bare = assertInstanceOf(FtbText.OpenUrl.class,
                parse("{open_url:https://example.invalid}").get(0));
        assertEquals("https://example.invalid", bare.url());
        assertEquals("https://example.invalid", bare.label(), "without words the address is its own");

        FtbText.OpenUrl labelled = assertInstanceOf(FtbText.OpenUrl.class,
                parse("{open_url:https://example.invalid Our Site}").get(0));
        assertEquals("https://example.invalid", labelled.url());
        assertEquals("Our Site", labelled.label());

        FtbText.Substitute substitute = assertInstanceOf(FtbText.Substitute.class,
                parse("{substitute:quest.intro}").get(0));
        assertEquals("quest.intro", substitute.key());
    }

    @Test
    @DisplayName("a raw JSON component arrives whole, with its words flattened")
    void jsonComponent() {
        FtbText.JsonText component = assertInstanceOf(FtbText.JsonText.class,
                parse("{\"text\": \"Hello \", \"extra\": [{\"text\": \"there\"}]}").get(0));
        assertEquals("Hello there", component.fallback());

        FtbText.JsonText keyed = assertInstanceOf(FtbText.JsonText.class,
                parse("{\"translate\": \"quest.a.title\"}").get(0));
        assertEquals("quest.a.title", keyed.fallback(), "a key with no fallback is its own words");
    }

    @Test
    @DisplayName("a brace that is prose stays prose")
    void strayBracesAreProse() {
        assertEquals("a { b", onlyText("a { b").text(), "an unclosed brace is words");
        assertEquals("{note}", onlyText("{note}").text(), "an unknown token is words");
        assertEquals("{oops", onlyText("{oops").text());
        assertEquals("{}", onlyText("{}").text(), "empty braces are words, not a component");
    }

    @Test
    @DisplayName("rainbow is one opaque ink per character, advancing around the wheel")
    void rainbowIsAStaticGradient() {
        List<FtbText.Segment> segments = parse("&zAB");
        assertEquals(2, segments.size(), "no two gradient steps share an ink: " + segments);
        for (FtbText.Segment segment : segments) {
            FtbText.Text run = assertInstanceOf(FtbText.Text.class, segment);
            assertEquals(1, run.text().length());
            assertEquals(0xFF000000, run.argb() & 0xFF000000, "opaque: " + run);
        }
        assertTrue(assertInstanceOf(FtbText.Text.class, segments.get(0)).argb()
                != assertInstanceOf(FtbText.Text.class, segments.get(1)).argb(),
                "adjacent steps differ");

        assertEquals(0xFFFF0000, FtbText.rainbowArgb(0f), "the wheel starts at red");
        assertEquals(0xFF00FFFF, FtbText.rainbowArgb(0.5f), "and crosses cyan halfway");
    }

    @Test
    @DisplayName("nothing in means nothing out, and obfuscated is recorded for the consumer")
    void emptyAndObfuscated() {
        assertTrue(parse("").isEmpty());
        assertTrue(onlyText("&kMagic").obfuscated(), "recorded here, degraded where it is drawn");
    }

    @Test
    @DisplayName("one ink reads the words without it, and keeps what the author escaped")
    void plainStrips() {
        assertEquals("Chapter 2: The ATM Star", FtbText.plain("&aChapter 2&r: &6The ATM Star"));
        assertEquals("Forbidden & Arcanus", FtbText.plain("Forbidden \\& Arcanus"));
        assertEquals("Fish & Chips", FtbText.plain("Fish & Chips"),
                "a marker before no code is words, not ink");
        assertEquals("Punch a Tree", FtbText.plain("Punch a Tree"), "plain prose passes through");
        assertEquals("", FtbText.plain(""));
    }

    @Test
    @DisplayName("a page boundary splits the paragraph, and each page renders its own words")
    void pagesSplit() {
        List<FtbText.Rendered> pages = FtbText.pages("First page{@pagebreak}Second page", key -> "");
        assertEquals(2, pages.size());
        assertEquals("First page", pages.get(0).residual());
        assertEquals("Second page", pages.get(1).residual());

        List<FtbText.Rendered> single = FtbText.pages("&aGreen", key -> "");
        assertEquals(1, single.size(), "no boundary is one page");
        assertEquals("Green", single.get(0).residual(), "the residual is the words without the ink");
        assertEquals(1, single.get(0).spans().size());
        assertEquals(0xFF55FF55, single.get(0).spans().get(0).argb());
    }

    @Test
    @DisplayName("a rendered paragraph tiles its residual, so a lookup by index always answers")
    void spansTileTheResidual() {
        FtbText.Rendered rendered =
                FtbText.pages("&aGreen &6gold {open_url:https://example.invalid Site}", key -> "")
                        .get(0);

        assertEquals("Green gold Site", rendered.residual());
        List<FtbText.Span> spans = rendered.spans();
        assertEquals(0, spans.get(0).start(), "the first span starts where the words do");
        for (int i = 1; i < spans.size(); i++) {
            assertEquals(spans.get(i - 1).end(), spans.get(i).start(),
                    "no gaps and no overlaps between spans");
        }
        assertEquals(rendered.residual().length(), spans.get(spans.size() - 1).end(),
                "and the last span ends where the words do");

        assertEquals(0xFF55FF55, FtbText.spanAt(spans, 0).argb(), "green words wear green");
        assertEquals(0xFF55FF55, FtbText.spanAt(spans, 5).argb(),
                "the join rides with the words before it, where no eye can see it");
        assertEquals(0xFFFFAA00, FtbText.spanAt(spans, 6).argb(), "gold words wear gold");
        FtbText.Span link = FtbText.spanAt(spans, 12);
        assertEquals("https://example.invalid", link.link(), "the site opens its address");
        assertEquals("Site", rendered.residual().substring(link.start(), link.end()));
    }

    @Test
    @DisplayName("substitutions resolve, pictures are consumed, and components fall back to words")
    void specialSegmentsRender() {
        FtbText.Rendered rendered = FtbText.pages(
                "See {substitute:quest.intro} here {image:pack:textures/x.png width:10 height:10}",
                key -> key.equals("quest.intro") ? "the start" : "").get(0);

        assertEquals("See the start here ", rendered.residual(),
                "the lookup resolves, and the picture contributes no words");
        assertTrue(rendered.spans().stream().noneMatch(span -> !span.link().isEmpty()),
                "and neither opens anything");

        FtbText.Rendered missing = FtbText.pages("See {substitute:quest.gone} here", key -> null)
                .get(0);
        assertEquals("See  here", missing.residual(),
                "a missing key arrives as blank rather than as a raw key on screen");

        FtbText.Rendered component =
                FtbText.pages("{\"text\": \"Hello \"}", key -> "").get(0);
        assertEquals("Hello", component.residual(), "a component reads its fallback");
    }

    @Test
    @DisplayName("struck words arrive underlined, because the seam has no strike")
    void strikeDegradesToUnderline() {
        FtbText.Rendered rendered = FtbText.pages("&mStruck", key -> "").get(0);
        assertTrue(rendered.spans().get(0).underline(), "underlined, which survives the seam");
    }

    @Test
    @DisplayName("pictures stand at offsets between the words, in segment order")
    void imagesStandAtOffsets() {
        FtbText.Rendered rendered = FtbText.pages(
                "See {image:pack:textures/a.png width:150 height:75 align:center} this", key -> "")
                .get(0);

        assertEquals("See  this", rendered.residual(), "pictures contribute no words");
        assertEquals(1, rendered.images().size());
        FtbText.PlacedImage placed = rendered.images().get(0);
        assertEquals(4, placed.offset(), "where the token stood");
        assertEquals("pack:textures/a.png", placed.image().src());
        assertEquals(150, placed.image().width());
        assertEquals(75, placed.image().height());
        assertEquals("center", placed.image().align());
    }

    @Test
    @DisplayName("a picture fits its column, scaling down and never up past it")
    void imagesFitTheirColumn() {
        FtbText.InlineImage asked = new FtbText.InlineImage("pack:textures/a.png", 150, 75, "center",
                java.util.Map.of());
        FtbText.ImageBox fitted = FtbText.fitImage(asked, 400);
        assertEquals(150, fitted.width());
        assertEquals(75, fitted.height(), "asked size, when it fits");

        FtbText.ImageBox clamped = FtbText.fitImage(asked, 100);
        assertEquals(100, clamped.width(), "clamped to the column");
        assertEquals(50, clamped.height(), "and scaled proportionally with it");

        FtbText.ImageBox fallback = FtbText.fitImage(
                new FtbText.InlineImage("pack:textures/a.png", 0, 0, "left", java.util.Map.of()), 400);
        assertEquals(64, fallback.width());
        assertEquals(64, fallback.height(), "the legacy default when the token names no size");
    }

    @Test
    @DisplayName("coloured words draw in runs, and plain words draw as one text call")
    void drawTruncatedDrawsRuns() {
        dev.ellipog.tenet.client.render.RecordingRenderer r =
                dev.ellipog.tenet.client.render.RecordingRenderer.create();

        int advance = FtbText.drawTruncated(r, "&aGreen", 10, 20, 400, FtbText.WHITE);
        assertEquals(r.textWidth("Green"), advance, "the advance is the words' own width");
        assertTrue(r.texts().isEmpty(), "a coloured run is not a text call");
        assertEquals(1, r.styled().size(), "but one styled one");

        r.reset();
        int plain = FtbText.drawTruncated(r, "Punch a Tree", 10, 20, 400, FtbText.WHITE);
        assertEquals(r.textWidth("Punch a Tree"), plain);
        assertEquals(1, r.texts().size(), "untokened words draw exactly as before");
        assertTrue(r.styled().isEmpty());
    }

    @Test
    @DisplayName("a title too wide for its room is cut with an ellipsis, colours kept")
    void drawTruncatedCuts() {
        dev.ellipog.tenet.client.render.RecordingRenderer r =
                dev.ellipog.tenet.client.render.RecordingRenderer.create();
        int room = r.textWidth("Green ") + r.textWidth("…");

        int advance = FtbText.drawTruncated(r, "&aGreen Tree", 0, 0, room, FtbText.WHITE);
        assertEquals(room, advance, "cut words plus the ellipsis fill the room exactly");
        assertTrue(r.drewText("…"), "the cut is marked");
        assertTrue(r.styled().size() >= 1, "and what drew wore its ink");
    }
}
