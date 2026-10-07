package dev.ellipog.tenet.quest;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.chat.Component;

import java.util.Optional;

/**
 * A piece of text in a quest file, which may or may not be translatable.
 *
 * <p>In JSON it is either a plain string, which is taken as literal text:
 *
 * <pre>{@code "title": "Punch a Tree"}</pre>
 *
 * <p>or an object naming a translation key, with an English fallback for the case where a
 * translation is missing:
 *
 * <pre>{@code "title": { "translate": "quest.tenet.punch_a_tree", "fallback": "Punch a Tree" }}</pre>
 *
 * <p>Both forms exist because both are needed and neither can be the only one. A pack author
 * writing a questline for a single language wants to type the title. A mod shipping a default
 * questline wants it translatable. Requiring the object form everywhere makes the first case
 * painful; allowing only strings makes the second impossible.
 *
 * <p>The fallback is what stops a missing translation from showing a raw key in the UI, which is
 * the single most common way a mod looks unfinished.
 */
public record QuestText(String value, boolean translatable, Optional<String> fallback) {

    public static final QuestText EMPTY = new QuestText("", false, Optional.empty());

    private record TranslateSpec(String translate, Optional<String> fallback) {
        static final Codec<TranslateSpec> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("translate").forGetter(TranslateSpec::translate),
                Codec.STRING.optionalFieldOf("fallback").forGetter(TranslateSpec::fallback)
        ).apply(instance, TranslateSpec::new));
    }

    public static final Codec<QuestText> CODEC = Codec.either(Codec.STRING, TranslateSpec.CODEC)
            .xmap(QuestText::fromEither, QuestText::toEither);

    /**
     * A list of paragraphs that also accepts one bare string.
     *
     * <h2>Why the union lives here rather than at each field that wants it</h2>
     *
     * <p>It was written inline on {@link ChapterGroup} for a chapter group's description, and it was
     * right there for the reason its own javadoc gives: a one-line description written as a string is
     * not a mistake worth failing a file over. The same is true of a group's description in the
     * version-2 {@code group.json} — the field moved and the argument came with it — and two copies of
     * a {@code Codec.either} plus its two {@code xmap} lambdas is two places for the one-line case to
     * stop working.
     *
     * <p>So it is a codec here, beside the type whose values it carries. The asymmetry in the
     * {@code xmap} is deliberate and is the whole trick: <b>a one-element list comes back as a bare
     * string</b>, which means a round trip through a codec normalises rather than preserving the
     * spelling. That is correct for a file format whose two spellings are equivalent — and it is worth
     * naming, because a test that asserted "the string I wrote comes back as a string" would be
     * asserting something the format does not promise.
     */
    public static final Codec<java.util.List<QuestText>> LIST_OR_ONE =
            Codec.either(CODEC, CODEC.listOf())
                    .xmap(either -> either.map(java.util.List::of, list -> list),
                            list -> list.size() == 1
                                    ? Either.left(list.get(0))
                                    : Either.right(list));

    public static QuestText literal(String text) {
        return new QuestText(text, false, Optional.empty());
    }

    public static QuestText translatable(String key, String fallback) {
        return new QuestText(key, true, Optional.of(fallback));
    }

    private static QuestText fromEither(Either<String, TranslateSpec> either) {
        return either.map(QuestText::literal,
                spec -> new QuestText(spec.translate(), true, spec.fallback()));
    }

    private Either<String, TranslateSpec> toEither() {
        return translatable
                ? Either.right(new TranslateSpec(value, fallback))
                : Either.left(value);
    }

    /**
     * The text to show a player.
     *
     * <p>Resolved at display time rather than at load time, so that switching language takes effect
     * without reloading the quest files — {@code Component} does the looking-up.
     */
    public Component component() {
        if (!translatable) {
            return Component.literal(value);
        }
        return fallback
                .<Component>map(text -> Component.translatableWithFallback(value, text))
                .orElseGet(() -> Component.translatable(value));
    }

    @Override
    public String toString() {
        return translatable ? value + " (translatable)" : value;
    }
}
