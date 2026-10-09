package dev.ellipog.tenet.quest;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.List;
import java.util.Set;

/**
 * Everything drawn on a chapter's canvas that is not a quest: the decorations behind the nodes and
 * the markers pointing at other quests.
 *
 * <h2>Why this grouping exists, and why it is invisible in JSON</h2>
 *
 * <p>Because {@code RecordCodecBuilder} stops at sixteen components and a chapter with separate
 * {@code elements} and {@code links} fields would be seventeen. The grouping is only visible in
 * Java: the codec is a {@link MapCodec}, so both lists stay flat on the chapter — {@code "elements"}
 * and {@code "links"} beside {@code "quests"} — exactly as if they were fields of their own. That
 * is the same arrangement {@link ChapterRules} makes for a chapter's gate and {@link QuestLayout}
 * makes for a quest's position, and for the same reason: a file about where things go should not
 * need a level of nesting to say so.
 *
 * <p>A chapter keeps its {@code elements()} and {@code links()} accessors, so no caller learns
 * this record's name: it is a codec's answer to a codec's limit, not a concept an author meets.
 */
public record ChapterCanvas(List<CanvasElement> elements, List<QuestLink> links) {

    /** The field names this contributes, for the validator to allow at chapter level. */
    public static final Set<String> FIELDS = Set.of("elements", "links");

    public static final MapCodec<ChapterCanvas> MAP_CODEC = RecordCodecBuilder.mapCodec(
            instance -> instance.group(
                    // Decoration, and the codec is the element's own: the tree writes what the
                    // chapter reads, so a field added to an element travels without anybody
                    // remembering to send it. See CanvasElement#asJson.
                    CanvasElement.CODEC.listOf().optionalFieldOf("elements", List.of())
                            .forGetter(ChapterCanvas::elements),
                    // Markers, by the link's own codec for the same reason: a converted chapter's
                    // six links travel exactly as written, and a future link field needs no
                    // second reader to be remembered.
                    QuestLink.CODEC.listOf().optionalFieldOf("links", List.of())
                            .forGetter(ChapterCanvas::links)
            ).apply(instance, ChapterCanvas::new));

    public ChapterCanvas {
        elements = List.copyOf(elements);
        links = List.copyOf(links);
    }
}
