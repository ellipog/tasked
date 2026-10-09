package dev.ellipog.tenet.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * FTB Quests' text tokens, read at render time into segments.
 *
 * <h2>Why a parser, and why here rather than in the file format</h2>
 *
 * <p>Real pack text carries {@code &} colour/style codes, {@code &#RRGGBB} hex, {@code &z} rainbow,
 * an author-escaped {@code \&}, {@code {@pagebreak}}, {@code {image:...}}, {@code {open_url:...}},
 * {@code {substitute:...}}, and rarely a raw JSON text component. The quest schema does not change
 * for any of them: the converter passes pack text through verbatim, and this parser — applied after
 * the locale overlay has resolved, where the reader's prose is built — is what gives the tokens
 * meaning. A token nobody recognises stays literal text, because a description with one stray brace
 * in it should read as prose rather than as a parse error.
 *
 * <p>Game-free, like the prose rules it sits beside: a pure function of a string, asserted without
 * a client. Colour/style resolution stops here too — no Armature release — because the card already
 * draws one styled call per piece, so a prepass emitting coloured runs slots into the existing loop.
 * Two degradations are recorded rather than hidden: obfuscated ({@code &k}) has no run flag to land
 * on and arrives as plain, and rainbow ({@code &z}) is a static per-character gradient rather than
 * an animation, so it costs no repaint.
 *
 * <h2>Vanilla semantics for the vanilla codes</h2>
 *
 * <p>A colour code sets the ink and keeps the flags; a style code sets its flag and keeps the ink;
 * {@code &r} resets both. That is what {@code §} does in the game, and {@code &} is its author-side
 * spelling here — both markers, either letter case. A marker at the end of the string, or before a
 * character that is no code, is literal text: dropping it would eat words.
 */
public final class FtbText {

    private FtbText() {
    }

    /** One piece of a parsed string, in order. */
    public sealed interface Segment {
    }

    /**
     * Visible words with their own look.
     *
     * @param argb plain prose arrives white
     * @param link a URL this run opens, or empty; only ever http/https by the time a consumer draws
     *             it — the opener refuses anything else
     */
    public record Text(String text, boolean bold, boolean italic, boolean underline, boolean strike,
                       boolean obfuscated, int argb, String link) implements Segment {
    }

    /** A page boundary: the card after this starts a new page. */
    public record PageBreak() implements Segment {
    }

    /**
     * A picture inside the prose.
     *
     * @param src    the file path or sprite id, exactly as written; the {@code .png} suffix rule is
     *               the same one the chapter-image source uses, applied by the consumer
     * @param width  requested width in pixels, or 0 when the token names none
     * @param height requested height in pixels, or 0 when the token names none
     * @param align  {@code left}, {@code center} or {@code right}; anything else arrives as written
     *               and the consumer treats it as {@code center}
     * @param params every other {@code key:value} pair the token carried, in order, so a future
     *               property survives the parse even before a consumer reads it
     */
    public record InlineImage(String src, int width, int height, String align,
                              Map<String, String> params) implements Segment {
    }

    /**
     * A link the author wrote by hand.
     *
     * <p>Approximate by admission: FTB applies this to the text around it in ways this parser does
     * not reconstruct. What is pinned is the safe half — the URL is captured whole, and the consumer
     * draws it through the same http/https-only opener every other link uses. A label the token
     * carries travels beside it; without one the address is its own words.
     */
    public record OpenUrl(String url, String label) implements Segment {
    }

    /** A translation lookup, resolved by the consumer after the locale overlay has resolved. */
    public record Substitute(String key) implements Segment {
    }

    /**
     * A whole string that is a raw JSON text component rather than prose with tokens.
     *
     * @param fallback the component's visible words, flattened lossily (texts and translate keys,
     *                 in order); the consumer draws this, and the raw travels so a richer reader can
     *                 do better later. Unparseable JSON never arrives here — it stays prose.
     */
    public record JsonText(String raw, String fallback) implements Segment {
    }

    /** Plain prose arrives in this ink. */
    public static final int WHITE = 0xFFFFFFFF;

    /**
     * One styled span of a rendered paragraph: {@code [start, end)} into the residual.
     *
     * @param argb      the ink as {@code 0xAARRGGBB}; {@link #WHITE} is unstyled prose
     * @param link      a URL this span opens, or empty
     * @param underline struck-through text arrives with this set: the seam has no strikethrough run,
     *                  and an underline is the closest visible emphasis that survives it
     */
    public record Span(int start, int end, int argb, String link, boolean bold, boolean italic,
                       boolean underline) {
    }

    /**
     * One page of a paragraph: the visible words for the markdown pass, the FTB look over them, and
     * the pictures standing among them.
     *
     * <p>The spans tile the residual in order and cover every character of it: a lookup by index
     * always answers, so the drawing never has to ask what an unstyled character is. The pictures
     * are zero-width markers at residual offsets, in segment order: splitting the residual at their
     * offsets is how a caller lays text and pictures out in one flow.
     */
    public record Rendered(String residual, List<Span> spans, List<PlacedImage> images) {
    }

    /**
     * An inline picture standing at a residual offset.
     *
     * <p>Zero-width: it sits <i>between</i> characters rather than covering any, so a colour span
     * reaching across it continues on both sides. The offset is where the token stood.
     */
    public record PlacedImage(InlineImage image, int offset) {
    }

    /**
     * A picture's drawn box: fitted to the column, in screen pixels.
     *
     * <p>Width is what the token asked clamped to the column, height follows proportionally — which
     * is stretching only when the token's own numbers stretch, the same reading chapter pictures
     * get. A token naming no size arrives at 64 pixels square: the legacy default, and the honest
     * answer for a picture whose size nobody wrote down.
     */
    public record ImageBox(int width, int height) {
    }

    /** A token's picture fitted to a column of prose. Never empty: both sides are at least one pixel. */
    public static ImageBox fitImage(InlineImage image, int columnWidth) {
        int askedWidth = image.width() > 0 ? image.width() : image.height() > 0 ? image.height() : 64;
        int askedHeight =
                image.height() > 0 ? image.height() : image.width() > 0 ? image.width() : 64;
        int width = Math.max(1, Math.min(askedWidth, Math.max(1, columnWidth)));
        int height = Math.max(1, (int) Math.round((double) askedHeight * width / askedWidth));
        return new ImageBox(width, height);
    }

    /**
     * A paragraph as pages: split on {@code {@pagebreak}}, each page rendered.
     *
     * <p>One page per returned element, in order, each with no page boundary in it: the card this
     * feeds is a scrolling body rather than pages, so a boundary is a paragraph break and not a
     * pager state. An empty page contributes an empty residual rather than nothing, because two
     * breaks in a row are a wider gap and not one.
     *
     * @param substitute how a {@code {substitute:key}} resolves; missing keys arrive as blank rather
     *                   than as a raw key on screen
     */
    public static List<Rendered> pages(String text,
                                       java.util.function.Function<String, String> substitute) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(substitute, "substitute");
        List<Rendered> out = new ArrayList<>();
        StringBuilder residual = new StringBuilder();
        List<Span> spans = new ArrayList<>();
        List<PlacedImage> images = new ArrayList<>();
        for (Segment segment : parse(text)) {
            if (segment instanceof PageBreak) {
                out.add(new Rendered(residual.toString(), List.copyOf(spans), List.copyOf(images)));
                residual = new StringBuilder();
                spans = new ArrayList<>();
                images = new ArrayList<>();
                continue;
            }
            if (segment instanceof InlineImage image) {
                images.add(new PlacedImage(image, residual.length()));
                continue;
            }
            appendRendered(residual, spans, segment, substitute);
        }
        out.add(new Rendered(residual.toString(), List.copyOf(spans), List.copyOf(images)));
        return List.copyOf(out);
    }

    /** One segment's words onto the residual, with its look recorded over them. */
    private static void appendRendered(StringBuilder residual, List<Span> spans, Segment segment,
                                       java.util.function.Function<String, String> substitute) {
        switch (segment) {
            // Struck-through arrives underlined: the seam has no strikethrough run, and an underline
            // is the closest visible emphasis that survives it. Obfuscated is dropped to plain, and
            // both degradations are recorded rather than hidden.
            case Text text -> span(residual, spans, text.text(), text.argb(), "", text.bold(),
                    text.italic(), text.underline() || text.strike());
            case OpenUrl open -> span(residual, spans, open.label(), WHITE, open.url(), false, false,
                    true);
            case Substitute sub -> {
                String resolved = substitute.apply(sub.key());
                span(residual, spans, resolved == null ? "" : resolved, WHITE, "", false, false,
                        false);
            }
            case JsonText json -> span(residual, spans, json.fallback(), WHITE, "", false, false,
                    false);
            // An inline picture contributes no words: it is recorded with its offset above, and the
            // caller lays it out. Consumed rather than left as prose, because the alternative
            // is the raw token on screen.
            case InlineImage ignored -> {
            }
            case PageBreak ignored -> {
            }
        }
    }

    /** Words plus the look that covers exactly them. Empty words record nothing. */
    private static void span(StringBuilder residual, List<Span> spans, String words, int argb,
                             String link, boolean bold, boolean italic, boolean underline) {
        if (words.isEmpty()) {
            return;
        }
        int from = residual.length();
        residual.append(words);
        spans.add(new Span(from, residual.length(), argb, link, bold, italic, underline));
    }

    /** The look covering an index, or null where the residual has no words at all. */
    public static Span spanAt(List<Span> spans, int index) {
        for (Span span : spans) {
            if (index >= span.start() && index < span.end()) {
                return span;
            }
        }
        return null;
    }

    /**
     * Words with their FTB ink, drawn and truncated in one pass.
     *
     * <p>For the surfaces that draw one line in one ink but read strings that carry codes: quest titles
     * on the canvas, the card's header, and anywhere else a title is drawn from the entry in scope.
     * Colours are widthless, so measuring stays plain — what is drawn here is exactly as wide as the
     * stripped words the callers measured, truncated and laid out before calling.
     *
     * <p>Styles do not survive: bold and italic change widths, and a run drawn wider than measured is
     * a label that overruns its room, its backdrop and its collision box. Titles wear colours only;
     * emphasis lives in the card. Struck-through arrives underlined and obfuscated arrives plain, the
     * same degradations the prose pass makes. Links open nothing here — a title is not a press — so
     * their labels draw in the default ink with no underline. Pictures and page breaks contribute
     * nothing; substitutions resolve against the locale overlay.
     *
     * <p>A title with no tokens takes the plain path: one {@code text} call, exactly as before, so
     * ninety-nine titles in a hundred cost nothing and draw nothing differently.
     *
     * @return the advance, in pixels: where the next thing on the line starts
     */
    public static int drawTruncated(dev.ellipog.armature.client.render.GuiRenderer r, String text, int x,
                                    int y, int maxWidth, int defaultInk) {
        Objects.requireNonNull(r, "r");
        Objects.requireNonNull(text, "text");
        List<InkRun> runs = runs(text);
        int total = 0;
        for (InkRun run : runs) {
            total += r.textWidth(run.text());
        }
        if (maxWidth <= 0) {
            return 0;
        }
        if (total <= maxWidth) {
            drawRuns(r, runs, x, y, defaultInk);
            return total;
        }
        String ellipsis = "\u2026";
        int room = maxWidth - r.textWidth(ellipsis);
        int atX = x;
        int drawn = 0;
        if (room > 0) {
            int[] cut = drawCut(r, runs, x, y, room, defaultInk);
            atX = cut[0];
            drawn = cut[1];
        }
        else {
            int[] cut = drawCut(r, runs, x, y, maxWidth, defaultInk);
            atX = cut[0];
            drawn = cut[1];
            return drawn;
        }
        r.text(ellipsis, atX, y, defaultInk);
        return drawn + r.textWidth(ellipsis);
    }

    /**
     * Words with their FTB ink, drawn whole.
     *
     * <p>The untruncated half of {@link #drawTruncated}: same runs, same inks, no ellipsis. For the
     * lines nothing truncates — the card's header, where the state tag that follows is placed off
     * the width this returns.
     *
     * @return the advance, in pixels
     */
    public static int drawColored(dev.ellipog.armature.client.render.GuiRenderer r, String text, int x,
                                  int y, int defaultInk) {
        return drawTruncated(r, text, x, y, Integer.MAX_VALUE, defaultInk);
    }

    /** One drawable run: visible words in one ink, underline or not. */
    private record InkRun(String text, int ink, boolean underline) {
    }

    /**
     * A string's drawable runs, in order.
     *
     * <p>Colours kept, styles dropped for the reason {@link #drawTruncated} gives, links flattened to
     * their labels, pictures and page breaks skipped, substitutions resolved. Empty runs never
     * appear: a run with no words is nothing to draw and nothing to measure.
     */
    private static List<InkRun> runs(String text) {
        List<InkRun> runs = new ArrayList<>();
        for (Segment segment : parse(text)) {
            switch (segment) {
                case Text words -> {
                    if (!words.text().isEmpty()) {
                        runs.add(new InkRun(words.text(), words.argb(),
                                words.underline() || words.strike()));
                    }
                }
                case OpenUrl open -> {
                    if (!open.label().isEmpty()) {
                        runs.add(new InkRun(open.label(), WHITE, false));
                    }
                }
                case Substitute sub -> {
                    String resolved = ClientLocale.find(sub.key());
                    if (resolved != null && !resolved.isEmpty()) {
                        runs.add(new InkRun(resolved, WHITE, false));
                    }
                }
                case JsonText json -> {
                    if (!json.fallback().isEmpty()) {
                        runs.add(new InkRun(json.fallback(), WHITE, false));
                    }
                }
                default -> {
                }
            }
        }
        return runs;
    }

    /** Every run drawn whole, left to right. */
    private static void drawRuns(dev.ellipog.armature.client.render.GuiRenderer r, List<InkRun> runs,
                                 int x, int y, int defaultInk) {
        int atX = x;
        for (InkRun run : runs) {
            atX += drawRun(r, run, atX, y, defaultInk);
        }
    }

    /**
     * One run: plain text when the default ink does, one styled call when it does not.
     *
     * <p>The split is what keeps untokened titles byte-identical to before: a run in the default
     * ink with no underline is a {@code text} call, exactly as it always was, and only a run that
     * needs something else pays for a styled one.
     *
     * @return the run's advance
     */
    private static int drawRun(dev.ellipog.armature.client.render.GuiRenderer r, InkRun run, int x,
                               int y, int defaultInk) {
        if (run.ink() == defaultInk && !run.underline()) {
            r.text(run.text(), x, y, defaultInk);
        }
        else {
            r.styledText(
                    List.of(new dev.ellipog.armature.client.render.GuiRenderer.StyledRun(run.text(),
                            false, false, run.underline(), 1F)),
                    x, y, run.ink());
        }
        return r.textWidth(run.text());
    }

    /**
     * Runs drawn until the budget runs out, cut mid-run when it must be.
     *
     * @return the x after the last drawn ink, and the advance drawn
     */
    private static int[] drawCut(dev.ellipog.armature.client.render.GuiRenderer r, List<InkRun> runs,
                                 int x, int y, int budget, int defaultInk) {
        int atX = x;
        int drawn = 0;
        for (InkRun run : runs) {
            int width = r.textWidth(run.text());
            if (drawn + width <= budget) {
                atX += drawRun(r, run, atX, y, defaultInk);
                drawn += width;
                continue;
            }
            // Into the run character by character: a run is a label fragment, never a document, and
            // the linear walk cannot be off by one the way a hand-written binary search can.
            for (int i = 0; i < run.text().length(); i++) {
                String ch = run.text().substring(i, i + 1);
                int charWidth = r.textWidth(ch);
                if (drawn + charWidth > budget) {
                    break;
                }
                atX += drawRun(r, new InkRun(ch, run.ink(), run.underline()), atX, y, defaultInk);
                drawn += charWidth;
            }
            break;
        }
        return new int[] {atX, drawn};
    }

    /**
     * A string with every token read out of it: colours gone, escapes kept, brace tokens replaced
     * by the words they stand for.
     *
     * <p>For the surfaces that draw one ink: titles, labels, toasts and rows. A title carrying
     * {@code &a} reads the words without the ink; an escaped {@code \&} reads as {@code &}, because
     * that is what the author meant; a link reads its label; anything with no words reads as
     * nothing. An ampersand before no code is literal, so {@code Fish & Chips} survives — but
     * {@code R&B} does not, and that is documented where titles are: {@code &} starts a colour
     * code, FTB-style, and a literal one is written {@code \&}.
     */
    public static String plain(String text) {
        Objects.requireNonNull(text, "text");
        StringBuilder out = new StringBuilder();
        for (Segment segment : parse(text)) {
            switch (segment) {
                case Text words -> out.append(words.text());
                case OpenUrl open -> out.append(open.label());
                case JsonText json -> out.append(json.fallback());
                default -> {
                }
            }
        }
        return out.toString();
    }

    /**
     * The game's sixteen inks, {@code 0} through {@code f}, in order.
     *
     * <p>Vanilla's own palette, opaque throughout: these are the colours a pack author means by
     * {@code &a}, and matching them exactly is what makes a converted title the same green rather
     * than a nearby one.
     */
    private static final int[] PALETTE = {
            0xFF000000, 0xFF0000AA, 0xFF00AA00, 0xFF00AAAA,
            0xFFAA0000, 0xFFAA00AA, 0xFFFFAA00, 0xFFAAAAAA,
            0xFF555555, 0xFF5555FF, 0xFF55FF55, 0xFF55FFFF,
            0xFFFF5555, 0xFFFF55FF, 0xFFFFFF55, 0xFFFFFFFF,
    };

    /** The look words are accumulating in: flags, ink, and whether the ink is a gradient. */
    private static final class Look {
        boolean bold;
        boolean italic;
        boolean underline;
        boolean strike;
        boolean obfuscated;
        int argb = WHITE;
        boolean rainbow;
        float hue;

        Look copy() {
            Look out = new Look();
            out.bold = bold;
            out.italic = italic;
            out.underline = underline;
            out.strike = strike;
            out.obfuscated = obfuscated;
            out.argb = argb;
            out.rainbow = rainbow;
            out.hue = hue;
            return out;
        }

        boolean sameAs(Look other) {
            return bold == other.bold && italic == other.italic && underline == other.underline
                    && strike == other.strike && obfuscated == other.obfuscated
                    && !rainbow && !other.rainbow && argb == other.argb;
        }

        /** The ink one character is drawn in: its gradient step, or the flat ink. */
        int ink() {
            return rainbow ? rainbowArgb(hue) : argb;
        }
    }

    /**
     * The tokens in a string, in order.
     *
     * <p>Adjacent words with the same look arrive as one {@link Text}: merging is what keeps a
     * sentence with one code in it two runs rather than a run per character. Rainbow text never
     * merges — each character carries its own ink — which is the honest cost of a gradient. A string
     * that is a raw JSON component arrives as a single {@link JsonText} and nothing else.
     */
    public static List<Segment> parse(String text) {
        Objects.requireNonNull(text, "text");
        JsonText component = asComponent(text);
        if (component != null) {
            return List.of(component);
        }
        List<Segment> out = new ArrayList<>();
        StringBuilder words = new StringBuilder();
        Look look = new Look();
        Look pending = new Look();

        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '{') {
                Segment token = token(text, i);
                if (token != null) {
                    flush(out, words, pending);
                    out.add(token);
                    i = tokenEnd(text, i);
                    continue;
                }
            }
            if (c == '\\' && i + 1 < text.length() && text.charAt(i + 1) == '&') {
                // An escaped ampersand is one literal `&`.
                words = append(out, words, pending, look, "&");
                look.hue += hueStep();
                i += 2;
                continue;
            }
            if ((c == '&' || c == '§') && i + 1 < text.length()) {
                int after = consumeCode(text, i + 1, look);
                if (after > 0) {
                    words = flushed(out, words, pending, look);
                    i = after;
                    continue;
                }
                // No code: the marker is literal, and so is what follows.
            }
            words = append(out, words, pending, look, String.valueOf(c));
            look.hue += hueStep();
            i++;
        }
        flush(out, words, pending);
        return List.copyOf(out);
    }

    /**
     * One character onto the pending words, flushing first when the look changed.
     *
     * <p>Returns the builder to keep appending to: the same one while the look holds still, a fresh
     * one after a flush. Rainbow text flushes every character, because no two gradient steps share
     * an ink — {@link Look#sameAs} answers false whenever either side is a gradient.
     */
    private static StringBuilder append(List<Segment> out, StringBuilder words, Look pending, Look look,
                                        String word) {
        Look now = look.copy();
        now.argb = look.ink();
        if (words.length() > 0 && !pending.sameAs(now)) {
            flush(out, words, pending);
        }
        if (words.length() == 0) {
            pending.bold = now.bold;
            pending.italic = now.italic;
            pending.underline = now.underline;
            pending.strike = now.strike;
            pending.obfuscated = now.obfuscated;
            pending.argb = now.argb;
            pending.rainbow = false;
            words = new StringBuilder();
        }
        words.append(word);
        return words;
    }

    /** The pending words as one run, when there are any. */
    private static void flush(List<Segment> out, StringBuilder words, Look pending) {
        if (words.length() == 0) {
            return;
        }
        out.add(new Text(words.toString(), pending.bold, pending.italic, pending.underline,
                pending.strike, pending.obfuscated, pending.argb, ""));
        words.setLength(0);
    }

    /** The pending words flushed and their builder replaced, for a look change mid-sentence. */
    private static StringBuilder flushed(List<Segment> out, StringBuilder words, Look pending, Look look) {
        flush(out, words, pending);
        pending.bold = look.bold;
        pending.italic = look.italic;
        pending.underline = look.underline;
        pending.strike = look.strike;
        pending.obfuscated = look.obfuscated;
        pending.argb = look.ink();
        pending.rainbow = false;
        return new StringBuilder();
    }

    /**
     * A formatting code at {@code from}, applied to {@code look}.
     *
     * @return just past the code, or -1 when the marker opens no code and stays literal
     */
    private static int consumeCode(String text, int from, Look look) {
        char code = text.charAt(from);
        int colour = paletteIndex(code);
        if (colour >= 0) {
            look.argb = PALETTE[colour];
            look.rainbow = false;
            return from + 1;
        }
        if (code == '#' && isHex(text, from + 1, 6)) {
            look.argb = 0xFF000000 | Integer.parseUnsignedInt(text.substring(from + 1, from + 7), 16);
            look.rainbow = false;
            return from + 7;
        }
        switch (Character.toLowerCase(code)) {
            case 'k' -> look.obfuscated = true;
            case 'l' -> look.bold = true;
            case 'm' -> look.strike = true;
            case 'n' -> look.underline = true;
            case 'o' -> look.italic = true;
            case 'r' -> {
                look.bold = false;
                look.italic = false;
                look.underline = false;
                look.strike = false;
                look.obfuscated = false;
                look.argb = WHITE;
                look.rainbow = false;
            }
            case 'z' -> {
                // A static gradient until the next colour or reset — see the class note.
                look.rainbow = true;
                look.hue = 0f;
            }
            default -> {
                return -1;
            }
        }
        if (Character.toLowerCase(code) != 'z') {
            look.rainbow = false;
        }
        return from + 1;
    }

    /**
     * One of the brace tokens, or null when the brace opens prose.
     *
     * <p>Only the four this format gives meaning: {@code {@pagebreak}}, {@code {image:...}},
     * {@code {open_url:...}} and {@code {substitute:...}}. Anything else — a JSON component is
     * handled before this is ever asked, and a stray brace is prose — is null.
     */
    private static Segment token(String text, int from) {
        int end = text.indexOf('}', from);
        if (end < 0) {
            return null;
        }
        String body = text.substring(from + 1, end);
        if (body.equals("@pagebreak")) {
            return new PageBreak();
        }
        if (body.startsWith("image:")) {
            return image(body.substring("image:".length()));
        }
        if (body.startsWith("open_url:")) {
            String rest = body.substring("open_url:".length()).strip();
            if (rest.isEmpty()) {
                return null;
            }
            int split = rest.indexOf(' ');
            String url = split < 0 ? rest : rest.substring(0, split);
            String label = split < 0 ? rest : rest.substring(split + 1).strip();
            return new OpenUrl(url, label.isEmpty() ? url : label);
        }
        if (body.startsWith("substitute:")) {
            String key = body.substring("substitute:".length()).strip();
            return key.isEmpty() ? null : new Substitute(key);
        }
        return null;
    }

    /** Just past the token starting at {@code from}: after its closing brace. */
    private static int tokenEnd(String text, int from) {
        int end = text.indexOf('}', from);
        return end < 0 ? text.length() : end + 1;
    }

    /**
     * An inline picture's properties.
     *
     * <p>The source first, then {@code key:value} pairs separated by spaces — the shape converted
     * packs actually carry ({@code {image:path width:150 height:150 align:center}}). A pair without
     * a colon is ignored rather than refused: an unknown property must not cost the picture.
     */
    private static InlineImage image(String body) {
        String[] parts = body.strip().split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) {
            return new InlineImage("", 0, 0, "center", Map.of());
        }
        int width = 0;
        int height = 0;
        String align = "center";
        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 1; i < parts.length; i++) {
            int colon = parts[i].indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = parts[i].substring(0, colon).toLowerCase(java.util.Locale.ROOT);
            String value = parts[i].substring(colon + 1);
            switch (key) {
                case "width" -> width = number(value);
                case "height" -> height = number(value);
                case "align" -> align = value.toLowerCase(java.util.Locale.ROOT);
                default -> params.put(key, value);
            }
        }
        return new InlineImage(parts[0], width, height, align, Map.copyOf(params));
    }

    /** A whole-pixel number, or 0 when the token names something that is not one. */
    private static int number(String value) {
        try {
            return Math.max(0, Integer.parseInt(value));
        }
        catch (NumberFormatException notANumber) {
            return 0;
        }
    }

    /** The palette slot for a colour code, either marker and either case, or -1. */
    private static int paletteIndex(char code) {
        char lower = Character.toLowerCase(code);
        if (lower >= '0' && lower <= '9') {
            return lower - '0';
        }
        if (lower >= 'a' && lower <= 'f') {
            return 10 + lower - 'a';
        }
        return -1;
    }

    /** Whether {@code length} hex digits start at {@code from}. */
    private static boolean isHex(String text, int from, int length) {
        if (from + length > text.length()) {
            return false;
        }
        for (int i = from; i < from + length; i++) {
            char c = text.charAt(i);
            boolean hex = c >= '0' && c <= '9'
                    || c >= 'a' && c <= 'f'
                    || c >= 'A' && c <= 'F';
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    /** One hue step of the rainbow gradient: twelve characters around the wheel. */
    private static float hueStep() {
        return 1f / 12f;
    }

    /** The gradient's ink at one hue: full saturation and brightness, opaque. */
    static int rainbowArgb(float hue) {
        float h = hue - (float) Math.floor(hue);
        int sector = (int) (h * 6);
        float fraction = h * 6 - sector;
        int rising = Math.round(fraction * 255);
        int falling = Math.round((1 - fraction) * 255);
        int rgb = switch (sector % 6) {
            case 0 -> 0xFF0000 | rising << 8;
            case 1 -> 0x00FF00 | falling << 16;
            case 2 -> 0x00FF00 | rising;
            case 3 -> 0x0000FF | falling << 8;
            case 4 -> rising << 16 | 0x0000FF;
            default -> 0xFF0000 | falling;
        };
        return 0xFF000000 | rgb;
    }

    /**
     * Whether a string is a raw JSON text component, and its flattened words if so.
     *
     * <p>Strict on purpose: an object must hold at least one of {@code text}, {@code translate} or
     * {@code extra} — anything else, including a brace a description uses as prose (which Gson's
     * lenient reader would happily call an object), stays prose. An array must be non-empty. A
     * component that parses but holds no words arrives with an empty fallback rather than failing,
     * because a token the reader cannot draw is still a string the file was allowed to hold.
     */
    static JsonText asComponent(String text) {
        String stripped = text.strip();
        if (stripped.length() < 2) {
            return null;
        }
        char first = stripped.charAt(0);
        if (first != '{' && first != '[') {
            return null;
        }
        com.google.gson.JsonElement parsed;
        try {
            parsed = com.google.gson.JsonParser.parseString(stripped);
        }
        catch (com.google.gson.JsonSyntaxException notJson) {
            return null;
        }
        if (parsed.isJsonObject()) {
            com.google.gson.JsonObject object = parsed.getAsJsonObject();
            if (!object.has("text") && !object.has("translate") && !object.has("extra")) {
                return null;
            }
        }
        else if (parsed.isJsonArray()) {
            if (parsed.getAsJsonArray().isEmpty()) {
                return null;
            }
        }
        else {
            return null;
        }
        StringBuilder words = new StringBuilder();
        flatten(parsed, words);
        return new JsonText(stripped, words.toString().strip());
    }

    /** The visible words of a component, in order: {@code text}s, {@code translate} keys, extras. */
    private static void flatten(com.google.gson.JsonElement element, StringBuilder words) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonPrimitive()) {
            if (element.getAsJsonPrimitive().isString()) {
                words.append(element.getAsString());
            }
            return;
        }
        if (element.isJsonArray()) {
            for (com.google.gson.JsonElement each : element.getAsJsonArray()) {
                flatten(each, words);
            }
            return;
        }
        com.google.gson.JsonObject object = element.getAsJsonObject();
        if (object.has("text")) {
            flatten(object.get("text"), words);
        }
        else if (object.has("translate")) {
            // A key with no fallback on hand: the key itself, which is what the game shows when a
            // translation is missing too.
            com.google.gson.JsonElement key = object.get("translate");
            words.append(key.isJsonPrimitive() ? key.getAsString() : "");
        }
        if (object.has("extra")) {
            flatten(object.get("extra"), words);
        }
    }
}
