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
 * How a dependency line is drawn: its form, its arrows, its pattern, and its weight.
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
 * <h2>Arrows: one legacy axis, three current ones</h2>
 *
 * <p>The first version of this record had a single {@code arrows} axis ({@code none/one/both/many}),
 * which conflated the glyph with where it was placed. The current vocabulary splits that into
 * {@code arrowHead} (the glyph), {@code arrowPlace} (where the heads sit) and {@code arrowDensity}
 * (how far apart a stream repeats). Files and servers that still write the old axis are read through
 * it — see {@link #headOr}, {@link #placeOr} and {@link #arrowSpacing} — while the editor only ever
 * writes the new three. An old value therefore keeps drawing what it always drew, and a new build's
 * output is merely "unsaid" to an old one, which falls back to a chevron at the target.
 *
 * <p>The three new axes are appended after the handles rather than beside {@link #arrows} so the
 * historical component order — the order every existing constructor call and codec group reads —
 * stays untouched.
 */
public record DependencyStyle(Optional<Form> form, Optional<Arrows> arrows, Optional<Dash> dash,
                              Optional<Weight> weight, Optional<Double> bend, Optional<Double> fromAnchor, Optional<Double> toAnchor,
                              Optional<List<Double>> fromHandle,
                              Optional<List<Double>> toHandle,
                              Optional<ArrowHead> arrowHead, Optional<ArrowPlace> arrowPlace,
                              Optional<ArrowDensity> arrowDensity) {

    /** A line's shape. */
    public enum Form {

        /** The three-segment step: out vertically, across, in vertically. What the canvas always drew. */
        ORTHOGONAL("orthogonal"),

        /**
         * The orthogonal step with every 90-degree corner cut at a 45-degree chamfer: a circuit trace.
         *
         * <p>The built-in default, so a line that says nothing about its form gets this one.
         */
        CHAMFERED("chamfered"),

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

    /**
     * Where arrowheads are drawn.
     *
     * <p><b>Legacy</b>: kept so files and older servers that wrote it keep drawing what they drew. The
     * editor writes {@link ArrowHead}, {@link ArrowPlace} and {@link ArrowDensity} instead, and reading
     * maps through {@code headOr}/{@code placeOr}: {@code none} is no head, {@code one} is a chevron at
     * the target, {@code both} adds the departure head, and {@code many} is a stream.
     */
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

    /**
     * The line's pattern, unbroken or broken in one of several rhythms.
     *
     * <p>The file's axis is still called {@code dash} — renaming it would strand every existing pack —
     * but it grew past "solid or dashed": dots read as mysterious, a dash-dot as a cross-chapter link, a
     * double line as a backbone, and hatch marks as a hazard. {@code DOUBLE} ignores the weight axis,
     * because "two hairlines" is the whole point of it.
     */
    public enum Dash {

        SOLID("solid"),
        DASHED("dashed"),
        DOTTED("dotted"),
        DASH_DOT("dash_dot"),
        DOUBLE("double"),
        HAZARD("hazard");

        private final String wire;

        Dash(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }

        public static final Codec<Dash> CODEC = Codecs.enumByName(Dash.class);
    }

    /** How many pixels wide, in the vocabulary a person picks from. */
    public enum Weight {

        /** Hairline: one pixel. What an unconfigured line gets. */
        THIN("thin", 1),

        /** Standard: two pixels. */
        THICK("thick", 2),

        /** Bold: three pixels. */
        BOLD("bold", 3),

        /** A conduit: six pixels, dark edges around a lighter core. */
        CONDUIT("conduit", 6);

        private final String wire;
        private final int width;

        Weight(String wire, int width) {
            this.wire = wire;
            this.width = width;
        }

        public String wire() {
            return wire;
        }

        /** How many parallel runs this weight is drawn with. */
        public int width() {
            return width;
        }

        public static final Codec<Weight> CODEC = Codecs.enumByName(Weight.class);
    }

    /** The glyph an arrowhead is drawn as. */
    public enum ArrowHead {

        /** Two strokes: the light wireframe look. */
        CHEVRON("chevron"),

        /** A filled triangle: heavy and readable at a glance. */
        TRIANGLE("triangle"),

        /** A small bead, for "any one of these" joins where direction is not the point. */
        DOT("dot"),

        /** A filled rhombus: a milestone marker. */
        DIAMOND("diamond"),

        /** A blunt line end: connected, undirected. */
        NONE("none");

        private final String wire;

        ArrowHead(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }

        public static final Codec<ArrowHead> CODEC = Codecs.enumByName(ArrowHead.class);
    }

    /** Where a line's heads sit. */
    public enum ArrowPlace {

        /** One head at the dependent end. What an unconfigured line gets. */
        TARGET("target"),

        /** One at each end. */
        BOTH("both"),

        /** One head in the middle of the route, pointing along it. */
        MID("mid"),

        /** A repeated run of heads along the route, for a line that should read as flowing. */
        STREAM("stream");

        private final String wire;

        ArrowPlace(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }

        public static final Codec<ArrowPlace> CODEC = Codecs.enumByName(ArrowPlace.class);
    }

    /** How far apart a stream's heads repeat. */
    public enum ArrowDensity {

        LOW("low", 64),
        MEDIUM("medium", 32),
        HIGH("high", 16);

        private final String wire;
        private final int spacing;

        ArrowDensity(String wire, int spacing) {
            this.wire = wire;
            this.spacing = spacing;
        }

        public String wire() {
            return wire;
        }

        /** The gap between heads, in pixels. */
        public int spacing() {
            return spacing;
        }

        public static final Codec<ArrowDensity> CODEC = Codecs.enumByName(ArrowDensity.class);
    }

    /** How far apart the legacy {@code many} setting always drew its chevrons. */
    public static final int LEGACY_STREAM_SPACING = 24;

    /**
     * The axes a chapter's default may set, in the order a message names them.
     *
     * <p>Ordered rather than a set because a validator's "the settings are ..." is read by a person,
     * and a set's order is a hash's. Kept beside the record so a new axis cannot be added without the
     * vocabulary list in its path.
     */
    public static final List<String> SHARED_FIELDS =
            List.of("form", "arrowHead", "arrowPlace", "arrowDensity", "dash", "weight", "bend");

    /** The axes only one line may set: which rim it meets is a fact about its own two ends. */
    public static final List<String> LINE_FIELDS = List.of("fromAnchor", "toAnchor", "fromHandle", "toHandle");

    /** The legacy arrows axis: read for files that wrote it, no longer written by the editor. */
    public static final String LEGACY_ARROWS_FIELD = "arrows";

    /** Every axis a style may name, for anything that walks a style's fields. */
    public static final Set<String> FIELDS = fields();

    private static Set<String> fields() {
        java.util.LinkedHashSet<String> all = new java.util.LinkedHashSet<>(SHARED_FIELDS);
        all.addAll(LINE_FIELDS);
        all.add(LEGACY_ARROWS_FIELD);
        return java.util.Collections.unmodifiableSet(all);
    }

    /** How far a curve may bow, as a fraction of its chord. The drag clamps to this too. */
    public static final double MAX_BEND = 0.8;

    /** No axis said: the identity of {@link #over}, and what a file that names nothing means. */
    public static final DependencyStyle UNSET =
            new DependencyStyle(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

    /** What an axis is when nothing, anywhere, says otherwise. */
    public static final DependencyStyle BUILT_IN = new DependencyStyle(
            Optional.of(Form.CHAMFERED), Optional.empty(), Optional.of(Dash.SOLID),
            Optional.of(Weight.THIN), Optional.of(0.2), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(),
            Optional.of(ArrowHead.CHEVRON), Optional.of(ArrowPlace.TARGET),
            Optional.of(ArrowDensity.MEDIUM));

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
                toHandle.isPresent() ? toHandle : base.toHandle,
                arrowHead.isPresent() ? arrowHead : base.arrowHead,
                arrowPlace.isPresent() ? arrowPlace : base.arrowPlace,
                arrowDensity.isPresent() ? arrowDensity : base.arrowDensity);
    }

    /** Every axis set, the built-ins filling whatever is still unsaid. */
    public DependencyStyle resolved() {
        return over(BUILT_IN);
    }

    /** The form in force, with the fallback for a style that did not say. */
    public Form formOr(Form fallback) {
        return form.orElse(fallback);
    }

    /** The legacy arrows axis in force. Prefer {@link #headOr} and {@link #placeOr}. */
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

    /**
     * The glyph in force: the current axis, else what the legacy axis meant, else the fallback.
     *
     * <p>The legacy reading is the whole compatibility story: an old {@code many} was a chevron, and an
     * old {@code none} was no head at all — so a file written before this axis existed keeps drawing
     * exactly the glyph it drew.
     */
    public ArrowHead headOr(ArrowHead fallback) {
        if (arrowHead.isPresent()) {
            return arrowHead.get();
        }
        if (arrows.isPresent()) {
            return headOf(arrows.get());
        }
        return fallback;
    }

    /** Where the heads sit: the current axis, else what the legacy axis meant, else the fallback. */
    public ArrowPlace placeOr(ArrowPlace fallback) {
        if (arrowPlace.isPresent()) {
            return arrowPlace.get();
        }
        if (arrows.isPresent()) {
            return placeOf(arrows.get());
        }
        return fallback;
    }

    /** What a legacy arrows value meant as a glyph: one mapping, for every reader of the old axis. */
    public static ArrowHead headOf(Arrows arrows) {
        return arrows == Arrows.NONE ? ArrowHead.NONE : ArrowHead.CHEVRON;
    }

    /** What a legacy arrows value meant as a placement. */
    public static ArrowPlace placeOf(Arrows arrows) {
        return switch (arrows) {
            case NONE, ONE -> ArrowPlace.TARGET;
            case BOTH -> ArrowPlace.BOTH;
            case MANY -> ArrowPlace.STREAM;
        };
    }

    /** How far apart a stream repeats: the current axis, else the fallback. */
    public ArrowDensity densityOr(ArrowDensity fallback) {
        return arrowDensity.orElse(fallback);
    }

    /**
     * The gap a stream repeats at, in pixels.
     *
     * <p>A stream derived from the legacy {@code many} keeps the spacing {@code many} always had rather
     * than jumping to the medium density: the axis was added to give authors a choice, not to redraw
     * every pack that had already made one.
     */
    public int arrowSpacing() {
        if (arrowDensity.isPresent()) {
            return arrowDensity.get().spacing();
        }
        if (arrows.isPresent() && arrows.get() == Arrows.MANY) {
            return LEGACY_STREAM_SPACING;
        }
        return ArrowDensity.MEDIUM.spacing();
    }

    /** Whether this style says nothing at all — an override that could be removed rather than written. */
    public boolean isUnset() {
        return form.isEmpty() && arrows.isEmpty() && dash.isEmpty() && weight.isEmpty()
                && bend.isEmpty() && fromAnchor.isEmpty() && toAnchor.isEmpty()
                && fromHandle.isEmpty() && toHandle.isEmpty()
                && arrowHead.isEmpty() && arrowPlace.isEmpty() && arrowDensity.isEmpty();
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
        arrowHead.ifPresent(value -> json.addProperty("arrowHead", value.wire()));
        arrowPlace.ifPresent(value -> json.addProperty("arrowPlace", value.wire()));
        arrowDensity.ifPresent(value -> json.addProperty("arrowDensity", value.wire()));
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
                handle(json, "toHandle"),
                axis(json, "arrowHead", ArrowHead.values()),
                axis(json, "arrowPlace", ArrowPlace.values()),
                axis(json, "arrowDensity", ArrowDensity.values()));
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

    /** The axis enum a field name names, or null. One place the axis names are joined to their types. */
    public static Class<? extends Enum<?>> axisType(String field) {
        return switch (field) {
            case "form" -> Form.class;
            case "arrows" -> Arrows.class;
            case "dash" -> Dash.class;
            case "weight" -> Weight.class;
            case "arrowHead" -> ArrowHead.class;
            case "arrowPlace" -> ArrowPlace.class;
            case "arrowDensity" -> ArrowDensity.class;
            // `bend` is the one numeric axis: no enum to check a name against, so it answers null here
            // and the validator branches on the field name itself.
            default -> null;
        };
    }

    /**
     * Whether a field name is one of the three the legacy {@code arrows} axis used to say at once.
     *
     * <p>The split's one shared fact, because both writers that speak the new vocabulary — a line's
     * override and a chapter's default — must retire the old spelling in the same edit, or a value the
     * author replaced would come back the moment the new axis returned to "default".
     */
    public static boolean isArrowAxis(String field) {
        return switch (field) {
            case "arrowHead", "arrowPlace", "arrowDensity" -> true;
            default -> false;
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
            Codec.DOUBLE.listOf().optionalFieldOf("toHandle").forGetter(DependencyStyle::toHandle),
            ArrowHead.CODEC.optionalFieldOf("arrowHead").forGetter(DependencyStyle::arrowHead),
            ArrowPlace.CODEC.optionalFieldOf("arrowPlace").forGetter(DependencyStyle::arrowPlace),
            ArrowDensity.CODEC.optionalFieldOf("arrowDensity").forGetter(DependencyStyle::arrowDensity)
    ).apply(instance, DependencyStyle::new));
}
