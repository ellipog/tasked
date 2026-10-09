package dev.ellipog.tenet.quest;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.Set;

/**
 * How a quest presents itself: how wide its card wants to be, which of its edges are drawn, and
 * whether its arrival is announced.
 *
 * <p>Grouped into its own record for the mundane reason {@link QuestRules} and {@link QuestLayout}
 * give — {@code RecordCodecBuilder} stops at sixteen components and {@link Quest} is at fifteen
 * without these — and the real one: all but the last are about how a quest <i>reads</i> rather than
 * what it asks for or what gates it. In JSON the fields are <b>flat</b> on the quest, because this is a
 * {@link MapCodec}, exactly as a quest's rules and layout are flat on the quest. An author writes
 * {@code "minWidth": 250}, not a nested object.
 *
 * <p>{@code minWidth} is FTB Quests' {@code min_width}: the minimum width of the quest's detail
 * panel. Zero means unset — the panel takes its kind's default — because every file written before
 * this field existed says nothing, and nothing must read as the old behaviour. The chapter's
 * {@code defaultMinWidth} decides for a quest that says nothing; a quest's own value wins.
 *
 * <p>{@code hideDependentLines} is the outgoing half of {@code hideDependencyLines}. That flag
 * hides the lines <i>arriving</i> at a quest; this one hides the lines <i>leaving</i> it for its
 * dependants. FTB Quests ships both halves ({@code hide_dependency_lines} and
 * {@code hide_dependent_lines}); this mod had only the incoming side, so a converted pack's 375
 * uses of the outgoing half had nowhere to land.
 *
 * <p>{@code disableToast} is FTB Quests' {@code disable_toast} on the quest: completing this quest
 * raises no toast. It ORs with the silent auto-claim modes rather than replacing them — an author
 * who turned auto-claim on for fifty starter quests and an author who quieted one quest by name are
 * answering the same question from opposite ends. Tasks and rewards carry their own flag beside
 * their own common settings; see {@link TaskCommon} and
 * {@link dev.ellipog.tenet.quest.reward.RewardCommon}.
 *
 * <p>{@code ignoreRewardBlocking} is FTB Quests' {@code ignore_reward_blocking} on the quest: when
 * the team's payouts are held, this quest's rewards keep flowing. Either the quest's flag or a
 * reward's own flag exempts that reward — the quest's covers every reward on it, the reward's
 * covers just itself. See {@link dev.ellipog.tenet.progress.ProgressService#isBlocked}.
 *
 * <p>{@code requiresStageTeam} qualifies the quest's {@code requiresStage} gate (which lives in
 * {@link QuestRules} because the rules record filled first): read the team's stages rather than the
 * player's own. One member's induction then opens the quest for everybody. It sits here for the
 * mundane codec reason above, flat on the quest like everything else in this record.
 */
public record QuestPresentation(int minWidth, boolean hideDependentLines, boolean disableToast,
                                boolean ignoreRewardBlocking, boolean requiresStageTeam) {

    /** The bounds of {@code minWidth}, taken from FTB Quests' own editor (0 to 3000). */
    public static final int MIN_WIDTH_MIN = 0;
    public static final int MIN_WIDTH_MAX = 3000;

    /** A quest that says nothing about how it presents: unset width, drawn edges, announced. */
    public static final QuestPresentation DEFAULT = new QuestPresentation(0, false, false, false, false);

    /** The field names this contributes, for the validator to allow at quest level. */
    public static final Set<String> FIELDS =
            Set.of("minWidth", "hideDependentLines", "disableToast", "ignoreRewardBlocking",
                    "requiresStageTeam");

    public static final MapCodec<QuestPresentation> MAP_CODEC = RecordCodecBuilder.mapCodec(
            instance -> instance.group(
                    // Zero is unset rather than an error: the panel's kind decides, and a file that
                    // predates this field must read as the old behaviour. Out-of-range values are
                    // clamped by the codec and warned about by the validator, like `size` — a panel
                    // 4000 wide is a typo, not a file to refuse.
                    Codec.intRange(MIN_WIDTH_MIN, MIN_WIDTH_MAX)
                            .optionalFieldOf("minWidth", 0).forGetter(QuestPresentation::minWidth),
                    Codec.BOOL.optionalFieldOf("hideDependentLines", false)
                            .forGetter(QuestPresentation::hideDependentLines),
                    Codec.BOOL.optionalFieldOf("disableToast", false)
                            .forGetter(QuestPresentation::disableToast),
                    // A held payout still pays this quest: the quest-level half of the reward flag
                    // with the same name. Absent is held like everything else, which is what every
                    // file written before this field existed says.
                    Codec.BOOL.optionalFieldOf("ignoreRewardBlocking", false)
                            .forGetter(QuestPresentation::ignoreRewardBlocking),
                    // Which set the stage gate reads: the player's own, or the team's. Beside the
                    // gate it qualifies, flat on the quest like everything else here.
                    Codec.BOOL.optionalFieldOf("requiresStageTeam", false)
                            .forGetter(QuestPresentation::requiresStageTeam)
            ).apply(instance, QuestPresentation::new));

    public static final Codec<QuestPresentation> CODEC = MAP_CODEC.codec();
}
