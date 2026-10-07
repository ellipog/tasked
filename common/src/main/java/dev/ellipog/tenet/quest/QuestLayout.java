package dev.ellipog.tenet.quest;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.ellipog.armature.api.data.Codecs;

import java.util.Set;

/**
 * Where a quest sits, and what shape it is drawn with.
 *
 * <p>Grouped into its own record only because a flat {@code Quest} would have seventeen fields and
 * {@code RecordCodecBuilder} stops at sixteen. It is worth keeping on its own account anyway: this
 * is the part the editor changes when you drag a node, and the part a code change never touches.
 *
 * <p>In JSON the fields are <b>flat</b> on the quest itself — {@code "x"}, {@code "y"},
 * {@code "shape"}, {@code "size"} — because the codec is a {@link MapCodec}, not a nested object. A
 * quest file should not need an extra level of nesting to say where a box goes.
 *
 * <p>Grid spacing in the shipped chapters is 32 pixels, so quests at 0,0 and 32,0 touch. The editor
 * snaps to 8, which divides that, so a dragged node lands on a multiple of 8; a hand-edited file can
 * put one anywhere.
 */
public record QuestLayout(int x, int y, QuestShape shape, int size, double iconScale, int rotation) {

    /**
     * The share of the node the icon is asked to fill, unless a quest says otherwise.
     *
     * <p>A share of the <b>node</b>, not of whatever square the chosen shape happens to hold: a circle
     * that could hold less draws the largest item it can instead of quietly applying three-quarters to a
     * smaller square, which is what made one frame's item look lost and another's cramped at the same
     * setting.
     *
     * <p>Three-quarters rather than the full square, because at full size a node is a picture with a
     * one-pixel outline around it — which looks fine for one quest and like a contact sheet for fifty.
     * Leaving a margin lets the shape read, and the shape is the thing that says what kind of quest
     * this is.
     */
    public static final double DEFAULT_ICON_SCALE = 0.75;

    /**
     * How far a node is turned, in degrees clockwise on screen, unless a quest says otherwise.
     *
     * <p>Every shape can be turned, because a rotation is a turn of the <i>question</i> a shape answers
     * rather than a second table of spans — see {@code Shapes.rotated}. What it is for is the map: a
     * diamond at 45 degrees is a square, a gear's teeth can be phased off the grid, and a tome can lean.
     */
    public static final int DEFAULT_ROTATION = 0;

    /** The rotation's bounds, in degrees. A full turn is the shape itself, so 360 is not allowed. */
    public static final int MIN_ROTATION = 0;
    public static final int MAX_ROTATION = 359;

    /**
     * The node's size bounds and default, in pixels.
     *
     * <p>The bounds live here rather than in the codec, the validator and the client separately, which
     * is where they used to be: they are a property of the record the field belongs to, and three
     * copies of a range is three chances for one to move. Bounded because the canvas draws at a fixed
     * scale — a 4000-pixel node would be a performance problem and is certainly a typo for 40.
     */
    public static final int MIN_SIZE = 16;
    public static final int MAX_SIZE = 512;
    public static final int DEFAULT_SIZE = 48;

    public static final QuestLayout DEFAULT =
            new QuestLayout(0, 0, QuestShape.ROUNDED, DEFAULT_SIZE, DEFAULT_ICON_SCALE, DEFAULT_ROTATION);

    /** The field names this contributes, for the validator to allow at quest level. */
    public static final Set<String> FIELDS =
            Set.of("x", "y", "shape", "size", "iconScale", "rotation");

    public static final MapCodec<QuestLayout> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.INT.optionalFieldOf("x", 0).forGetter(QuestLayout::x),
            Codec.INT.optionalFieldOf("y", 0).forGetter(QuestLayout::y),
            QuestShape.CODEC.optionalFieldOf("shape", QuestShape.ROUNDED).forGetter(QuestLayout::shape),
            // Clamped rather than range-checked, and the three fields below are the ones in this format
            // where that is the right trade. A layout number is presentation: a node drawn at 4000
            // pixels, or turned 360 degrees, is a number the author got wrong, not a file this build
            // cannot read — and refusing the file costs every quest in it. `Codec.intRange` would report
            // an error and the document would not decode at all.
            //
            // The precedent is the wire's, not a new invention: `ClientQuestCache` already clamps an
            // out-of-range `iconScale` to the shape's own bounds rather than dropping the quest, so a
            // file codec that refused the file was the two halves of one format disagreeing about one
            // number. The bounds still come from the constants above and from QuestShape, so the codec,
            // the validator and the client cannot disagree about them.
            //
            // Not extended to the numbers that are a task's *meaning* -- an item count, a required
            // value -- where silently reading 0 as 1 would change what the quest asks for. Those keep
            // their range check; see `Codecs.clampedInt` for the argument in full.
            Codecs.clampedInt(MIN_SIZE, MAX_SIZE).optionalFieldOf("size", DEFAULT_SIZE)
                    .forGetter(QuestLayout::size),
            // The bounds live on QuestShape, where the geometry they describe lives, so the codec and
            // the validator cannot come to disagree about them.
            Codecs.clampedDouble(QuestShape.MIN_ICON_SCALE, QuestShape.MAX_ICON_SCALE)
                    .optionalFieldOf("iconScale", DEFAULT_ICON_SCALE).forGetter(QuestLayout::iconScale),
            // Degrees, and a whole turn is written as 0 rather than 360: the geometry treats them as the
            // same shape, and one spelling of "not turned" is one thing for a file to say. A file that
            // says 360 anyway is read as the top of the range rather than refused.
            Codecs.clampedInt(MIN_ROTATION, MAX_ROTATION)
                    .optionalFieldOf("rotation", DEFAULT_ROTATION).forGetter(QuestLayout::rotation)
    ).apply(instance, QuestLayout::new));

    public static final Codec<QuestLayout> CODEC = MAP_CODEC.codec();
}
