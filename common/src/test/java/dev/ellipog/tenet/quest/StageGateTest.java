package dev.ellipog.tenet.quest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import dev.ellipog.tenet.progress.ProgressService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stage gate on a quest: the field in the format, and what the engine answers when nobody can ask.
 *
 * <h2>What is asked here, and what is not</h2>
 *
 * <p>The gate has five parts -- the field, the per-player view, the completion refusal, the claim
 * refusal and the automatic payouts (the tick's and the login sweep's), so a member who never held the
 * stage is not paid when their team finishes the quest. Only the first is game-free. The other four need
 * a server that holds stages, which the playthrough harness has and a unit test does not; what this
 * covers is that the field is real in the format, that a typo in it is refused rather than silently
 * ignored, and that a gate nobody can ask about does not block (the harness runs with players whose
 * server is null, so that case is a live one rather than a hypothetical).
 */
@DisplayName("the stage gate")
class StageGateTest {

    private static Quest quest(String json) {
        JsonObject object = JsonParser.parseString(json).getAsJsonObject();
        return Quest.CODEC.parse(JsonOps.INSTANCE, object)
                .getOrThrow(error -> new AssertionError("the fixture did not decode: " + error));
    }

    @Test
    @DisplayName("a quest carries the stage it needs, and one that names none carries nothing")
    void theFieldRoundTrips() {
        Quest gated = quest("{\"id\": \"a\", \"title\": \"A\", \"requiresStage\": \"my_pack:chapter_one\"}");
        assertTrue(gated.requiresStage().isPresent(), "the gate is part of the format, not of the engine only");
        assertTrue(gated.requiresStage().get().toString().equals("my_pack:chapter_one"));
        assertFalse(gated.requiresStageTeam(), "absent reads the player's own stages");

        Quest open = quest("{\"id\": \"b\", \"title\": \"B\"}");
        assertFalse(open.requiresStage().isPresent(), "and saying nothing is a quest with no gate");
    }

    @Test
    @DisplayName("a team gate reads the team's stages (T22)")
    void teamGateRoundTrips() {
        Quest gated = quest("{\"id\": \"a\", \"title\": \"A\", \"requiresStage\": \"my_pack:chapter_one\","
                + " \"requiresStageTeam\": true}");
        assertTrue(gated.requiresStageTeam());

        JsonObject encoded = Quest.CODEC.encodeStart(JsonOps.INSTANCE, gated)
                .getOrThrow(error -> new AssertionError("a team-gated quest did not encode: " + error))
                .getAsJsonObject();
        assertTrue(encoded.has("requiresStageTeam"), "the team gate is written back: " + encoded);
    }

    @Test
    @DisplayName("a stage name that is not an id is refused at the codec, not quietly carried")
    void namesMustBeIds() {
        // The same rule every other name in this format follows, and the reason is the same: a stage
        // written "Chapter One" would never match a grant, and a typo the engine cannot see is a quest
        // nobody can open.
        var result = Quest.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(
                "{\"id\": \"a\", \"title\": \"A\", \"requiresStage\": \"Chapter One\"}"));

        assertTrue(result.error().isPresent(), "a non-id gate should be rejected: " + result);
    }

    @Test
    @DisplayName("a gate nobody can ask about is open, which is what keeps the harness working")
    void anUnaskableGateIsOpen() {
        Quest gated = quest("{\"id\": \"a\", \"title\": \"A\", \"requiresStage\": \"my_pack:chapter_one\"}");

        // The server is null in the playback harness and in the moment before a server is handed over; the
        // alternative -- blocking -- would refuse a completion over a fact that cannot be read.
        assertTrue(ProgressService.stageGateOpen(null, gated, UUID.randomUUID()));
        assertTrue(ProgressService.stageGateOpen(null, quest("{\"id\": \"b\", \"title\": \"B\"}"),
                UUID.randomUUID()), "and an ungated quest is open however unaskable the server is");
    }

    @Test
    @DisplayName("the validator knows the field, rather than reporting it as one nothing understands")
    void theValidatorKnowsTheField() {
        // A shape-level check only: the value's id-ness is the codec's question, asserted above. What this
        // guards is the field being missing from the allowed set, which would make every gated quest in a
        // pack warn about a typo that is not one.
        assertTrue(QuestRules.FIELDS.contains("requiresStage"));
    }

    @Test
    @DisplayName("the gate survives a full quest round trip, not just a direct field read")
    void survivesTheWholeQuest() {
        Quest gated = quest("{\"id\": \"a\", \"title\": \"A\", \"requiresStage\": \"my_pack:chapter_one\"}");
        JsonObject encoded = Quest.CODEC.encodeStart(JsonOps.INSTANCE, gated)
                .getOrThrow(error -> new AssertionError("a gated quest did not encode: " + error))
                .getAsJsonObject();

        assertTrue(encoded.has("requiresStage"), "the gate is written back: " + encoded);
        assertTrue(Quest.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow().requiresStage()
                .equals(Optional.of(gated.requiresStage().get())));
    }
}
