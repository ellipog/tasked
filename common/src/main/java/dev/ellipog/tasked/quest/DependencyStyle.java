package dev.ellipog.tasked.quest;

import dev.ellipog.armature.api.data.Codecs;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * How a dependency line is drawn: its form, its arrows, whether it is dashed, and its weight.
 *
 * <h2>Two places, one shape — and axes that may be unsaid</h2>
 *
 * <p>A chapter declares a default for all of its lines ({@code dependencyStyle}); a single line can
 * override it ({@code dependencyLines}, keyed by the dependency it styles). Both are this record and
 * both parse from the same object shape, which is what keeps them from drifting into two vocabularies.
 *
 * <p>Every axis is <b>optional here</b>, and that is the whole reason for the {@link Optional} fields:
 * an override that says {@code {"form":"curved"}} must change the form and leave the arrows, the dash
 * and the weight to whatever the chapter says. A record of concrete enums would have to invent values
 * for the three axes it was not told about, and inventing them is how a line's arrows would silently
 * reset to the built-in default whenever somebody set its form.
 *
 * <p>{@link #over} layers one style on another and {@link #resolved} fills what is left from the
 * built-ins, so "what colour is this line" is one expression at the drawing site.
 *
 * <h2>Arrows default to one, which changes every existing graph</h2>
 *
 * <p>Until this existed the canvas drew undirected lines and no arrows at all, so a pack nobody edits
 * is about to gain an arrowhead per edge. That is deliberate: a dependency has a direction — the quest
 * that waits on the other — and the arrow is the thing that says so. Defaulting to none would have
 * shipped the feature invisible.
 */
public record DependencyStyle(Optional<Form> form, Optional<Arrows> arrows, Optional<Dash> dash,
                              Optional<Weight> weight, Optional<Double> bend, Optional<Double> fromAnchor, Optional<Double> toAnchor,
                              Optional<List<Double>> fromHandle,
                              Optional<List<Double>> toHandle) {

    /** A line's shape. */
    public enum Form {

        /** The three-segment step: out vertically, across, in vertically. What the canvas always drew. */
        ORTHOGONAL("orthogonal"),

        /** A direct line from one node to the other. */
        STRAIGHT("straight"),

        /** A smooth bow, so parallel routes through a crowded graph can be told apart. */
        CURVED("curved");

        private final String wire;

        Form(String wire) {
            this.wire = wire;
        }

        /** The name this axis takes in a file. */
        public String wire() {
            return wire;
        }

        public static final Codec<Form> CODEC = Codecs.enumByName(Form.class);
    }

    /** Where arrowheads are drawn. */
    public enum Arrows {

        /** No arrowheads: the line says "connected" and nothing about direction. */
        NONE("none"),

        /** One, at the dependent end. What an unconfigured line gets. */
        ONE("one"),

        /** One at each end. */
        BOTH("both"),

        /** A repeating chevron along the line, for a route that should read as flowing. */
        MANY("many");

        private final String wire;

        Arrows(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }

        public static final Codec<Arrows> CODEC = Codecs.enumByName(Arrows.class);
    }

    /** Whether the line is drawn in runs or unbroken. */
    public enum Dash {

        SOLID("solid"),
        DASHED("dashed");

        private final String wire;

        Dash(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }

        public static final Codec<Dash> CODEC = Codecs.enumByName(Dash.class);
    }

    /** How many pixels wide. */
    public enum Weight {

        THIN("thin"),
        THICK("thick");

        private final String wire;

        Weight(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }

        public static final Codec<Weight> CODEC = Codecs.enumByName(Weight.class);
    }

    /** The axis names, for the validator to allow and for anything that walks a style's fields. */
    public static final Set<String> FIELDS = Set.of("form", "arrows", "dash", "weight", "bend", "fromAnchor", "toAnchor", "fromHandle",
            "toHandle");

    /** How far a curve may bow, as a fraction of its chord. The drag clamps to this too. */
    public static final double MAX_BEND = 0.8;

    /** No axis said: the identity of {@link #over}, and what a file that names nothing means. */
    public static final DependencyStyle UNSET =
            new DependencyStyle(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty());

    /** What an axis is when nothing, anywhere, says otherwise. */
    public static final DependencyStyle BUILT_IN = new DependencyStyle(
            Optional.of(Form.ORTHOGONAL), Optional.of(Arrows.ONE), Optional.of(Dash.SOLID),
            Optional.of(Weight.THIN), Optional.of(0.2), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty());

    /**
     * This style with every axis it does not set taken from {@code base}.
     *
     * <p>The operator the resolution is built from: an override over a chapter's default over the
     * built-ins, each layer filling only what the layer above left unsaid.
     */
    public DependencyStyle over(DependencyStyle base) {
        return new DependencyStyle(
                form.isPresent() ? form : base.form,
                arrows.isPresent() ? arrows : base.arrows,
                dash.isPresent() ? dash : base.dash,
                weight.isPresent() ? weight : base.weight,
                bend.isPresent() ? bend : base.bend,
                fromAnchor.isPresent() ? fromAnchor : base.fromAnchor,
                toAnchor.isPresent() ? toAnchor : base.toAnchor,
                fromHandle.isPresent() ? fromHandle : base.fromHandle,
                toHandle.isPresent() ? toHandle : base.toHandle);
    }

    /** Every axis set, the built-ins filling whatever is still unsaid. */
    public DependencyStyle resolved() {
        return over(BUILT_IN);
    }

    /** The form in force, with the fallback for a style that did not say. */
    public Form formOr(Form fallback) {
        return form.orElse(fallback);
    }

    /** The arrows in force. */
    public Arrows arrowsOr(Arrows fallback) {
        return arrows.orElse(fallback);
    }

    /** The dash in force. */
    public Dash dashOr(Dash fallback) {
        return dash.orElse(fallback);
    }

    /** The weight in force. */
    public Weight weightOr(Weight fallback) {
        return weight.orElse(fallback);
    }

    /** The bow in force, as a fraction of the chord. The curve's own default when nothing said. */
    public double bendOr(double fallback) {
        return bend.orElse(fallback);
    }

    /** The angle this line leaves its source node at, or null for the automatic rim point. */
    public Double fromAnchorOrNull() {
        return fromAnchor.orElse(null);
    }

    /** The angle this line meets its target node at, or null for the automatic rim point. */
    public Double toAnchorOrNull() {
        return toAnchor.orElse(null);
    }

    /** Whether this style says nothing at all — an override that could be removed rather than written. */
    public boolean isUnset() {
        return form.isEmpty() && arrows.isEmpty() && dash.isEmpty() && weight.isEmpty()
                && bend.isEmpty() && fromAnchor.isEmpty() && toAnchor.isEmpty()
                && fromHandle.isEmpty() && toHandle.isEmpty();
    }

    /**
     * The style as a file holds it: only the axes that were said.
     *
     * <p>Only the set ones, on purpose — a file that says `{"form":"curved"}` and then also carries
     * `"arrows":"one"` has stopped being an override and started pinning a value the author never chose.
     */
    public JsonObject asJson() {
        JsonObject json = new JsonObject();
        form.ifPresent(value -> json.addProperty("form", value.wire()));
        arrows.ifPresent(value -> json.addProperty("arrows", value.wire()));
        dash.ifPresent(value -> json.addProperty("dash", value.wire()));
        weight.ifPresent(value -> json.addProperty("weight", value.wire()));
        bend.ifPresent(value -> json.addProperty("bend", value));
        fromAnchor.ifPresent(value -> json.addProperty("fromAnchor", value));
        toAnchor.ifPresent(value -> json.addProperty("toAnchor", value));
        fromHandle.ifPresent(value -> json.add("fromHandle", pair(value)));
        toHandle.ifPresent(value -> json.add("toHandle", pair(value)));
        return json;
    }

    /**
     * The style a JSON object describes, leniently.
     *
     * <p>Lenient because this is the read path: an axis whose value this build does not know is
     * <b>left unsaid</b> rather than refusing the tree — a client must be able to draw a pack written by
     * a newer server. Saying so is the validator's job, at the file's own line, on the side that can
     * refuse it.
     */
    public static DependencyStyle from(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return UNSET;
        }
        JsonObject json = element.getAsJsonObject();
        return new DependencyStyle(
                axis(json, "form", Form.values()),
                axis(json, "arrows", Arrows.values()),
                axis(json, "dash", Dash.values()),
                axis(json, "weight", Weight.values()),
                number(json, "bend"),
                number(json, "fromAnchor"),
                number(json, "toAnchor"),
                handle(json, "fromHandle"),
                handle(json, "toHandle"));
    }

    /** One axis, by its wire name, case-insensitively. Empty for absent or unknown. */
    private static <E extends Enum<E>> Optional<E> axis(JsonObject json, String key, E[] values) {
        JsonElement element = json.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            return Optional.empty();
        }
        String name = element.getAsString();
        for (E value : values) {
            if (value.name().equalsIgnoreCase(name)) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }

    /** A `[along, across]` pair as JSON. */
    private static JsonArray pair(List<Double> value) {
        JsonArray array = new JsonArray();
        array.add(value.get(0));
        array.add(value.get(1));
        return array;
    }

    /** A `[along, across]` pair from JSON, empty unless it is exactly two numbers. */
    private static Optional<List<Double>> handle(JsonObject json, String key) {
        JsonElement element = json.get(key);
        if (element == null || !element.isJsonArray() || element.getAsJsonArray().size() != 2) {
            return Optional.empty();
        }
        List<Double> pair = new java.util.ArrayList<>();
        for (JsonElement each : element.getAsJsonArray()) {
            if (!each.isJsonPrimitive() || !each.getAsJsonPrimitive().isNumber()) {
                return Optional.empty();
            }
            pair.add(each.getAsDouble());
        }
        return Optional.of(List.copyOf(pair));
    }

    /** A numeric axis, empty when absent or not a number. */
    private static Optional<Double> number(JsonObject json, String key) {
        JsonElement element = json.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            return Optional.empty();
        }
        return Optional.of(element.getAsDouble());
    }

    /** Whether a wire name is one this build knows, for a validator's message. */
    public static boolean known(Class<? extends Enum<?>> axis, String name) {
        for (Enum<?> value : axis.getEnumConstants()) {
            if (value.name().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /** The axis enum a field name names, or null. One place the four names are joined to their types. */
    public static Class<? extends Enum<?>> axisType(String field) {
        return switch (field) {
            case "form" -> Form.class;
            case "arrows" -> Arrows.class;
            case "dash" -> Dash.class;
            case "weight" -> Weight.class;
            // `bend` is the one numeric axis: no enum to check a name against, so it answers null here
            // and the validator branches on the field name itself.
            default -> null;
        };
    }

    /** Every axis is optional, and one an older file does not carry is simply unsaid. */
    public static final Codec<DependencyStyle> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Form.CODEC.optionalFieldOf("form").forGetter(DependencyStyle::form),
            Arrows.CODEC.optionalFieldOf("arrows").forGetter(DependencyStyle::arrows),
            Dash.CODEC.optionalFieldOf("dash").forGetter(DependencyStyle::dash),
            Weight.CODEC.optionalFieldOf("weight").forGetter(DependencyStyle::weight),
            Codec.DOUBLE.optionalFieldOf("bend").forGetter(DependencyStyle::bend),
            // Per line only: the validator refuses these in a chapter's default, because a chapter's
            // lines meet different rims. They live here so the per-line object has one shape.
            Codec.DOUBLE.optionalFieldOf("fromAnchor").forGetter(DependencyStyle::fromAnchor),
            Codec.DOUBLE.optionalFieldOf("toAnchor").forGetter(DependencyStyle::toAnchor),
            Codec.DOUBLE.listOf().optionalFieldOf("fromHandle").forGetter(DependencyStyle::fromHandle),
            Codec.DOUBLE.listOf().optionalFieldOf("toHandle").forGetter(DependencyStyle::toHandle)
    ).apply(instance, DependencyStyle::new));
}
