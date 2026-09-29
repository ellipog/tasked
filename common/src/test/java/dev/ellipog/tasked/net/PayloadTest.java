package dev.ellipog.tasked.net;

import dev.ellipog.armature.api.net.ArmatureNetwork;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The payloads: their ids, and whether they survive a round trip.
 *
 * <h2>Why this exists, specifically</h2>
 *
 * <p>Because a wrong payload id <b>crashed a real server on startup</b>, and nothing in the build
 * noticed. The code was:
 *
 * <pre>{@code
 * CustomPacketPayload.createType(ResourceLocation.fromNamespaceAndPath("tasked", "quest_sync").toString())
 * }</pre>
 *
 * <p>which reads as though it names the payload {@code tasked:quest_sync}. It does not.
 * {@code createType} is {@code new Type(ResourceLocation.withDefaultNamespace(name))} — it takes a
 * <i>path</i> and hardcodes the {@code minecraft} namespace. So the location became
 * {@code minecraft:tasked:quest_sync}, and the colon left inside the path threw from a static
 * initialiser:
 *
 * <pre>Non [a-z0-9/._-] character in path of location: minecraft:tasked:quest_sync</pre>
 *
 * <p>The failure was a single line in a server log after a 40-second boot. This test finds it in the
 * build in milliseconds, and it keeps finding it — the namespace assertion below is written over
 * <i>every</i> declared payload rather than the three that exist today, so the fourth one cannot
 * reintroduce the same mistake.
 *
 * <h2>The round trip is the other half</h2>
 *
 * <p>A codec with its fields in the wrong order compiles, registers, and then mangles every packet
 * it ever sends — which shows up as a client showing the wrong quest count, or a disconnect, or
 * nothing at all. Encoding and decoding an instance through a real Minecraft buffer catches it
 * without a server.
 *
 * <p>One thing makes that possible without a running game: {@code RegistryAccess.EMPTY}. None of
 * these codecs touch a registry — they are all {@code ByteBufCodecs} primitives over
 * {@code ByteBuf} — so an empty registry access is enough to construct the buffer the codecs expect.
 */
@DisplayName("Network payloads")
class PayloadTest {

    @BeforeAll
    static void declarePayloads() {
        // The real declarations, so this tests the payloads that ship rather than copies of them.
        TaskedNetworking.declare();
    }

    // ------------------------------------------------------------------
    // Ids
    // ------------------------------------------------------------------

    @Test
    @DisplayName("every payload is namespaced under the mod, not under minecraft")
    void everyPayloadIsNamespacedCorrectly() {
        List<ArmatureNetwork.Registration<?>> registrations = ArmatureNetwork.registrations();

        assertFalse(registrations.isEmpty(), "no payloads were declared at all");

        for (ArmatureNetwork.Registration<?> registration : registrations) {
            assertEquals("tasked", registration.type().id().getNamespace(),
                    "payload " + registration.type().id() + " is not in the tasked namespace. "
                            + "If this is 'minecraft', the id was built with CustomPacketPayload.createType, "
                            + "which prepends a namespace and leaves any colon inside the path.");
        }
    }

    @Test
    @DisplayName("and has no colon left in its path, which is what actually threw")
    void pathsContainNoColon() {
        // The namespace assertion above would pass for a payload named `tasked:tasked:quest_sync`
        // only by luck; this one names the illegal character directly.
        for (ArmatureNetwork.Registration<?> registration : ArmatureNetwork.registrations()) {
            String path = registration.type().id().getPath();
            assertFalse(path.contains(":"),
                    "the path of " + registration.type().id() + " contains a colon, which no "
                            + "ResourceLocation path may. The namespace has leaked into the path.");
        }
    }

    @Test
    @DisplayName("the three payloads this stage adds are all present, at the paths expected")
    void declaredPayloadsAreTheExpectedOnes() {
        List<String> ids = ArmatureNetwork.registrations().stream()
                .map(registration -> registration.type().id().toString())
                .sorted()
                .toList();

        assertEquals(List.of("tasked:progress_sync", "tasked:quest_sync", "tasked:submit_task"), ids);
    }

    @Test
    @DisplayName("each travels the direction it should")
    void directions() {
        // A payload registered the wrong way round is one that never arrives, and the symptom is a
        // screen that stays empty with nothing in either log.
        assertEquals(ArmatureNetwork.Direction.TO_CLIENT, directionOf("tasked:quest_sync"));
        assertEquals(ArmatureNetwork.Direction.TO_CLIENT, directionOf("tasked:progress_sync"));
        assertEquals(ArmatureNetwork.Direction.TO_SERVER, directionOf("tasked:submit_task"));
    }

    private static ArmatureNetwork.Direction directionOf(String id) {
        return ArmatureNetwork.registrations().stream()
                .filter(registration -> registration.type().id().toString().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no payload named " + id))
                .direction();
    }

    // ------------------------------------------------------------------
    // Round trips
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the quest tree survives a round trip, byte array and all")
    void questSyncRoundTrip() {
        // Non-ASCII and a newline in the tree, because the tree is JSON and JSON holds both -- and
        // because a byte-array codec that truncated or mis-sized would still pass on "{}".
        byte[] tree = "{\"quests\":[{\"title\":\"Punch a Tree \u2014 it's fine\"}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        QuestSyncPayload original = new QuestSyncPayload(7, 2, tree);

        QuestSyncPayload decoded = roundTrip(QuestSyncPayload.CODEC, original);

        assertEquals(7, decoded.questCount());
        assertEquals(2, decoded.chapterCount());
        assertEquals(tree.length, decoded.tree().length, "the byte array changed length");
        assertArrayEquals(tree, decoded.tree());
    }

    @Test
    @DisplayName("an empty tree round-trips as an empty tree, not as null")
    void questSyncEmptyTree() {
        // The "no quests loaded" case, which is what a fresh install sends. A codec that returned
        // null here would throw on the client at the exact moment a new player opens the book.
        QuestSyncPayload decoded = roundTrip(QuestSyncPayload.CODEC,
                new QuestSyncPayload(0, 0, new byte[0]));

        assertEquals(0, decoded.questCount());
        assertEquals(0, decoded.tree().length);
    }

    @Test
    @DisplayName("progress survives a round trip, including a UUID's sign bits")
    void progressSyncRoundTrip() {
        // The all-ones UUID is the case a hand-rolled two-long split gets wrong: as signed values
        // both halves are -1, and anything that treats them as positive loses the team id. The
        // real codec uses writeLong, so it is fine -- and this is the test that says so.
        UUID awkward = new UUID(-1L, -1L);
        byte[] data = "{\"states\":{\"a\":\"COMPLETED\"}}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ProgressSyncPayload original = new ProgressSyncPayload(awkward, 123_456L, data,
                ProgressSyncPayload.REASON_CHANGED);

        ProgressSyncPayload decoded = roundTrip(ProgressSyncPayload.CODEC, original);

        assertEquals(awkward, decoded.teamId(), "the UUID's bits did not survive");
        assertEquals(123_456L, decoded.gameTime());
        assertEquals(ProgressSyncPayload.REASON_CHANGED, decoded.reason());
        assertArrayEquals(data, decoded.data());
    }

    @Test
    @DisplayName("a normal UUID round-trips too, so the awkward case is not the only one tested")
    void progressSyncNormalUuid() {
        UUID team = UUID.fromString("3f2a1b4c-5d6e-7f80-9a0b-1c2d3e4f5061");
        ProgressSyncPayload decoded = roundTrip(ProgressSyncPayload.CODEC,
                new ProgressSyncPayload(team, 0L, new byte[]{1, 2, 3}, ProgressSyncPayload.REASON_JOIN));

        assertEquals(team, decoded.teamId());
        assertEquals(0L, decoded.gameTime(), "zero is a valid game time on the first tick");
        assertEquals(3, decoded.data().length);
    }

    @Test
    @DisplayName("a task submission survives a round trip, and a long id is refused rather than truncated")
    void submitTaskRoundTrip() {
        SubmitTaskPayload decoded = roundTrip(SubmitTaskPayload.CODEC,
                new SubmitTaskPayload("punch_a_tree", 3));

        assertEquals("punch_a_tree", decoded.questId());
        assertEquals(3, decoded.taskIndex());
    }

    @Test
    @DisplayName("a quest id longer than any real one is rejected at the codec, not accepted silently")
    void overlyLongQuestIdIsRejected() {
        // The client sends this field, so it is untrusted input. A bounded string codec is what stops
        // a client sending a megabyte of id and the server trying to look it up.
        String huge = "a".repeat(200);
        FriendlyByteBuf plain = new FriendlyByteBuf(Unpooled.buffer());
        RegistryFriendlyByteBuf buffer = RegistryFriendlyByteBuf.decorator(RegistryAccess.EMPTY).apply(plain);

        SubmitTaskPayload payload = new SubmitTaskPayload(huge, 0);

        boolean rejected = false;
        try {
            SubmitTaskPayload.CODEC.encode(buffer, payload);
        }
        catch (RuntimeException e) {
            rejected = true;
        }
        assertTrue(rejected, "the codec accepted a 200-character quest id");
    }

    // ------------------------------------------------------------------
    // The decoder must not invent data
    // ------------------------------------------------------------------

    @Test
    @DisplayName("decoding from an untouched buffer fails rather than producing a zeroed payload")
    void decodingAnEmptyBufferFails() {
        // A real hazard: a mis-sized packet leaves the decoder reading past what was written. It
        // must throw, because the alternative is a payload of zeroes that looks like a legitimate
        // "0 quests, empty tree" and quietly clears the client's cache.
        FriendlyByteBuf plain = new FriendlyByteBuf(Unpooled.buffer());
        RegistryFriendlyByteBuf buffer = RegistryFriendlyByteBuf.decorator(RegistryAccess.EMPTY).apply(plain);

        boolean threw = false;
        try {
            QuestSyncPayload.CODEC.decode(buffer);
        }
        catch (RuntimeException e) {
            threw = true;
        }
        assertTrue(threw, "decoding an empty buffer produced a payload instead of failing");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Encodes and decodes through a real Minecraft buffer.
     *
     * <p>{@code RegistryFriendlyByteBuf.decorator} is how a plain netty buffer becomes one the codecs
     * accept. {@code RegistryAccess.EMPTY} is sufficient because none of these codecs resolve a
     * registry — they are all {@code ByteBufCodecs} primitives.
     */
    private static <T extends CustomPacketPayload> T roundTrip(
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec, T payload) {
        FriendlyByteBuf plain = new FriendlyByteBuf(Unpooled.buffer());
        RegistryFriendlyByteBuf buffer = RegistryFriendlyByteBuf.decorator(RegistryAccess.EMPTY).apply(plain);

        codec.encode(buffer, payload);

        // A fresh buffer reads from index 0, so the same buffer decodes back. Asserted rather than
        // assumed, because a codec that consumed as it encoded would silently decode zeroes here.
        assertEquals(0, buffer.readerIndex(), "encoding moved the read index, so this is not a round trip");

        int written = buffer.writerIndex();
        T decoded = codec.decode(buffer);
        assertTrue(written > 0, "nothing was written");
        return decoded;
    }

    private static void assertArrayEquals(byte[] expected, byte[] actual) {
        org.junit.jupiter.api.Assertions.assertArrayEquals(expected, actual);
    }
}
