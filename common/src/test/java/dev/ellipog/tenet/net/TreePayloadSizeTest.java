package dev.ellipog.tenet.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import dev.ellipog.tenet.quest.Fixtures;
import dev.ellipog.tenet.quest.MinecraftTestBootstrap;
import dev.ellipog.tenet.quest.QuestIndex;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static dev.ellipog.tenet.quest.Fixtures.q;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a tree broadcast costs, against what a delta for one quest would cost.
 *
 * <h2>Why this is a test rather than a paragraph</h2>
 *
 * <p>The roadmap's last item is a tree delta, and it is the only one whose premise nobody had measured.
 * The premise is <i>true</i> — {@code QuestSync.sendTreeTo} says so in its own words, "the tree is
 * immutable for as long as the quest files are, so there is nothing for a delta to describe", and every
 * content edit therefore re-sends the whole tree to every player, compressed and chunked. What was missing
 * is the arithmetic: whether that whole-tree send is a rounding error or the reason a big pack is
 * unpleasant to author on. That is a number, and the honest way to settle a claim about a number is to
 * print it — this repository's own rule.
 *
 * <p>So this measures three things on a pack of {@link #QUESTS} generated quests: the JSON the client is
 * sent, the same after {@code SyncWire}'s compression, and one quest's share of it. The ratio is what a
 * delta would buy, and it is structural rather than platform-dependent, which is why it can be asserted
 * while the absolute sizes are only printed.
 *
 * <p><b>What this does not measure.</b> How often an edit happens, how many players are on the server, or
 * what a chunk's framing adds per message. Those belong to the server's own operation, not to this
 * encoding, and a delta's win is their product with the ratio below.
 */
@DisplayName("the tree payload's size")
class TreePayloadSizeTest {

    /** A pack big enough that the per-quest share is a fraction worth arguing about. */
    private static final int QUESTS = 500;

    @BeforeAll
    static void bootstrapMinecraft() {
        // The writer resolves every icon against the item registry, so the registry has to exist.
        MinecraftTestBootstrap.boot();
    }

    @Test
    @DisplayName("the whole tree goes out, and one quest is a fraction of it")
    void theWholeTreeGoesOut() {
        String[] quests = new String[QUESTS];
        for (int i = 0; i < QUESTS; i++) {
            // A shape like a real pack's: laid out on a grid, three tasks each, and chained, so the
            // encoding carries positions, a task list and a dependency -- the three fields a delta would
            // have to be able to change.
            quests[i] = q("q" + i)
                    .at((i % 25) * 40, (i / 25) * 40)
                    .tasks(3)
                    .dependsOn(i == 0 ? new String[0] : new String[] {"q" + (i - 1)})
                    .build();
        }
        QuestIndex index = Fixtures.indexOf(Fixtures.file(quests));

        byte[] json = QuestSync.treeAsJson(index);
        byte[] packed = SyncWire.pack(json);
        int oneQuest = oneQuestAsJson(json);

        System.out.printf("tree payload: %d quest(s), %d KiB of JSON, %d KiB packed, %d bytes per quest%n",
                index.questCount(), json.length / 1024, packed.length / 1024, oneQuest);

        assertTrue(oneQuest * 100 < json.length,
                "one quest is " + oneQuest + " bytes of a " + json.length + "-byte tree: a delta for a "
                        + "single edit is a hundredth of the send or less, which is the whole of D4's case");
        assertTrue(packed.length < json.length,
                "and the tree is compressed on the wire, so a delta would save the smaller of the two "
                        + "figures -- the comparison that matters is packed against packed");
    }

    /** The length of the first quest's own encoding, which is what a delta would carry for one edit. */
    private static int oneQuestAsJson(byte[] json) {
        JsonArray quests = JsonParser.parseString(new String(json, java.nio.charset.StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonArray("quests");
        assertTrue(quests.size() > 0, "the tree carries a quests array, one entry per quest");
        return quests.get(0).toString().length();
    }
}
