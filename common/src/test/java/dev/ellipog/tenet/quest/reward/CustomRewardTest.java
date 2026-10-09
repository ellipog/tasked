package dev.ellipog.tenet.quest.reward;

import dev.ellipog.tenet.quest.QuestReward;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The custom reward: its handler registry, and what a grant does with one.
 *
 * <p>Each test uses its own id, so their order does not matter -- the same rule the custom task's
 * tests follow, for the same shared static registry.
 */
@DisplayName("the custom reward")
class CustomRewardTest {

    private static CustomReward reward(String id) {
        return new CustomReward(RewardCommon.DEFAULT, id);
    }

    private static RewardContext context() {
        return new RewardContext(null, null, UUID.randomUUID(), "quest", "chapter", List.of(), null);
    }

    /** The behaviour the engine reads: the registered handler, through the type's wrapper. */
    private static RewardBehaviour<QuestReward> behaviourOf(CustomReward reward) {
        return RewardTypes.behaviourOf(reward)
                .orElseThrow(() -> new AssertionError(reward.id() + " is not a registered type"));
    }

    @Test
    @DisplayName("a registered handler grants when the engine asks it to")
    void aHandlerGrants() {
        List<String> granted = new java.util.ArrayList<>();
        CustomReward.CustomRewards.register("test:grants",
                (player, context) -> granted.add(context.questId()));

        assertDoesNotThrow(() -> behaviourOf(reward("test:grants")).grant(reward("test:grants"),
                context()));
        assertEquals(List.of("quest"), granted, "the handler ran, with the claim's quest attached");
    }

    @Test
    @DisplayName("a handler that throws grants nothing, and never crashes the claim")
    void aThrowingHandlerGrantsNothing() {
        // The crash this exists for, beside the custom task's: a script error inside a grant
        // propagated out of the claim. Grants are rare, so every throw logs -- and the claim
        // continues without what the handler would have given.
        CustomReward.CustomRewards.register("test:throws", (player, context) -> {
            throw new IllegalStateException("a script bug");
        });

        assertDoesNotThrow(() -> behaviourOf(reward("test:throws")).grant(reward("test:throws"),
                context()));
        assertTrue(CustomReward.CustomRewards.handler("test:throws").isPresent(),
                "and the handler stays registered: the next claim tries again rather than going quiet");
    }
}
