package dev.ellipog.tasked.server;

import dev.ellipog.armature.api.ArmatureApi;
import dev.ellipog.armature.api.data.DataProblem;
import dev.ellipog.armature.api.data.Problems;
import dev.ellipog.armature.api.platform.ArmaturePlatform;
import dev.ellipog.armature.api.platform.PlatformKind;
import dev.ellipog.armature.api.registry.Registrar;
import dev.ellipog.armature.api.teams.Teams;
import dev.ellipog.tasked.TaskedCommand;
import dev.ellipog.tasked.progress.ProgressService;
import dev.ellipog.tasked.progress.QuestState;
import dev.ellipog.tasked.quest.QuestIndex;
import dev.ellipog.tasked.quest.QuestLoader;
import dev.ellipog.tasked.quest.QuestFiles;
import dev.ellipog.tasked.quest.TaskedQuests;
import dev.ellipog.tasked.net.PartySnapshot;
import dev.ellipog.tasked.net.ProgressSyncPayload;
import dev.ellipog.tasked.net.QuestSync;
import dev.ellipog.tasked.net.TaskedNetworking;
import dev.ellipog.tasked.party.PartyMode;
import dev.ellipog.tasked.party.PartyStore;

import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 3's exit condition, played rather than argued.
 *
 * <h2>What this is for</h2>
 *
 * <p>The plan's bar for Stage 3 is: <i>"you can complete an entire questline through commands, with
 * no GUI, and two players on a dedicated server share progress correctly."</i> Until now the engine
 * was checked only as a set of pure functions — {@code ProgressionEngineTest} hands the resolver a
 * quest index and some progress and reads states back, which is the right way to test the <i>rules</i>
 * and says nothing about whether anything connects.
 *
 * <p>Everything between those rules and a player was unverified, and the gap is not theoretical:
 * {@code /tasked progress}, {@code submit} and {@code complete} all call
 * {@code getPlayerOrException()}, so none of them had ever actually run. They compile. Nothing proved
 * they execute.
 *
 * <h2>How it runs</h2>
 *
 * <p>A real server, a real world, a real {@code ServerPlayer} — see {@link HeadlessServer} for why the
 * GameTest framework could not be borrowed and its recipe was copied instead. Commands go through the
 * server's own dispatcher with the player attached as the source's entity, which is the same source
 * shape a command block gets when it acts on a player.
 *
 * <h2>The one seam, and it is worth naming</h2>
 *
 * <p>The engine's automatic half is driven by a <i>player tick</i> that fires from each loader's event,
 * and no loader is present in a test, so nothing fires it. {@link #tickUntil} calls
 * {@code ProgressService.tick} directly, which is what the loader would call. So the automatic
 * evaluation is exercised through its real entry point but not through the real <i>trigger</i>; the
 * trigger is three lines in {@code Tasked.listen} and is covered by the loader's own event tests.
 *
 * <h2>Why the transcript shows translation keys</h2>
 *
 * <p>A dedicated server has no language file loaded, so {@code Component.getString()} on a translatable
 * returns the key: the transcript reads {@code tasked.command.complete.done} rather than English. That
 * is why <b>nothing here asserts on output text</b>. The return value is the assertion — 0 for a
 * refusal, 1 for a change — which is also sturdier: rewording or translating a message cannot break
 * these tests, and a test that matched on English would pass while the message was wrong for everyone
 * not playing in it.
 *
 * <h2>Ordered, because a playthrough is a sequence</h2>
 *
 * <p>A quest unlocking is a consequence of the previous quest completing, so these run in order and
 * carry state between them deliberately. That is the opposite of the usual rule for tests and is the
 * point here: the thing being tested is a chain, and splitting it into independent cases would test
 * five invocations of the resolver instead of one playthrough.
 */
@DisplayName("Stage 3: a whole questline, played by command")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class QuestPlaythroughTest {

    /** The questline shipped in the jar, in the order it becomes available. */
    private static final List<String> THE_QUESTLINE = List.of(
            "punch_a_tree", "make_a_table", "stone_tools", "read_the_sign", "the_underground");

    /** Where the world, the config and the transcript go. Inside the build directory, so not committed. */
    private static final Path ROOT = Path.of("build", "playthrough");

    private static final List<String> TRANSCRIPT = new ArrayList<>();

    private static HeadlessServer server;
    private static ServerPlayer player;
    private static QuestLoader.Result loaded;

    /** The second player, spawned when the party tests start. Null until then. */
    private static ServerPlayer friend;

    /** The party's own id, which is also the key its progress is stored under. */
    private static UUID partyId;

    /**
     * The party {@code /tasked party create} formed, which is a different party from {@link #partyId}.
     *
     * <p>Kept separate rather than reusing {@code partyId}, and the reason is that they are built by
     * different means: {@code partyId} comes from Armature's API directly, and this one from the
     * command. Reusing the field would leave the command's tests unable to tell "the command used the
     * party that already existed" from "the command formed one".
     */
    private static UUID commandPartyId;

    /** What the last tick of the engine reported as changed. Empty when nothing moved. */
    private static java.util.Set<UUID> lastTick = java.util.Set.of();

    /** Every owner any tick has reported as changed, for the assertion that the engine reports at all. */
    private static final java.util.Set<UUID> changedEver = new java.util.LinkedHashSet<>();

    /**
     * Where the worked examples live, relative to the Gradle project directory.
     *
     * <p>The same directory {@code QuestIndexTest} reads and {@code tasked/tools/seed_quests.py} copies
     * from. Three readers of one directory, and no list of file names anywhere — because a list is a
     * thing that stops matching the directory, which is exactly how the first version of the seeding
     * went wrong.
     */
    private static final Path EXAMPLES = Path.of("..", "tools", "quests");

    /** The example files this test seeded, in load order. Set once, in {@code @BeforeAll}. */
    private static List<String> examples = List.of();

    // ------------------------------------------------------------------
    // Before and after
    // ------------------------------------------------------------------

    @BeforeAll
    static void startEverything() throws Exception {
        // Deleted first, every run. A world left over from a previous run carries its saved progress,
        // and "the playthrough passed on the second run and failed on the first" is a difference
        // nobody would guess the cause of. The same goes for the quest directory, which is now
        // something this test puts there itself.
        deleteRecursively(ROOT);
        Path configDir = ROOT.resolve("config");
        Files.createDirectories(configDir);

        // Seed the config directory before anything reads it, and by copying the same files an author
        // would -- this is the step `tasked/tools/seed_quests.py` performs, done in Java so a test does
        // not depend on a Python interpreter being on PATH.
        //
        // The test does it rather than the loader, and that is the whole point of this round: the mod
        // reads a quest directory and does nothing else, so a test that wants quests has to put them
        // there. Relying on the loader to create its own input was the design that had to go, because
        // a mod that seeds content into a player's config directory has decided something that is not
        // its to decide.
        examples = seedExamples(configDir);
        note("seeded " + examples.size() + " example questline(s) into " + configDir.resolve("tasked/quests"));

        server = HeadlessServer.start(ROOT.resolve("universe"));

        // Armature's platform layer, because TaskedQuests.reload() asks it for the config directory
        // and there is no loader here to provide one. Asserted to be uninstalled first: if something
        // else got there before this test, the config directory would be someone else's and every
        // result below would be about the wrong questline.
        assertThrows(IllegalStateException.class, ArmatureApi::platform,
                "Armature's platform layer was already installed before this test -- its config "
                        + "directory is not the one these assertions are about");
        ArmatureApi.install(new PlaythroughPlatform(configDir), new NothingIsRegistered());

        // The real load path, the one /tasked reload calls.
        loaded = TaskedQuests.reload();

        // Registered onto the server's own dispatcher, which is the one a typed command reaches.
        server.callOnServerThread(() -> {
            TaskedCommand.register(server.dispatcher());
            return null;
        });

        player = server.spawnPlayer("tasked-tester");

        note("server up: " + TaskedQuests.summary());
        note("player '" + player.getScoreboardName() + "' is on the player list: "
                + server.callOnServerThread(() -> server.server().getPlayerList().getPlayers().size()));
    }

    @AfterAll
    static void stopEverything() throws IOException {
        // The transcript is written even when a test failed, because the transcript is most of what a
        // failure needs: the commands that ran, in order, and what each of them said.
        Files.createDirectories(ROOT);
        Files.writeString(ROOT.resolve("transcript.txt"),
                String.join("\n", TRANSCRIPT) + "\n");
        System.out.println("    playthrough transcript: " + ROOT.resolve("transcript.txt").toAbsolutePath());

        if (server != null) {
            server.close();
        }
    }

    // ------------------------------------------------------------------
    // The playthrough
    // ------------------------------------------------------------------

    @Test
    @Order(1)
    @DisplayName("the examples this test seeded load with nothing wrong in them")
    void theSeededExamplesLoad() throws IOException {
        assertTrue(loaded.ok(), () -> "the seeded questlines did not load cleanly:\n" + render(loaded.problems()));

        assertEquals(examples.size(), loaded.filesFound(),
                "every seeded file should have been found and read");

        // Counted from the seeded file names, and this is the fourth version of this check.
        //
        // The first asserted `5 quests in 1 chapter from 1 file`. That was right until the second and
        // third questlines were written, at which point it failed for the one reason a test should never
        // fail: the content grew. A hardcoded 5 was a claim about the content wearing the clothes of a
        // check on the loader.
        //
        // The second derived the expected numbers by *walking the index* -- it compared the index against
        // itself, so it could only fail if the index were internally inconsistent, which it never is. It
        // read as a check on the loader and was a tautology.
        //
        // The third decoded every seeded file and added up the groups, chapters and quests inside them.
        // That was a genuine independent count under version 1, where a file <i>is</i> a whole tree -- and
        // it is impossible under version 2, which is why this had to change rather than be repaired:
        // `group.json` decodes to a manifest holding a list of <b>names</b>, and the names are not in the
        // document at all. They are the directory listing. A test that decoded the files and counted what
        // was inside them would count three manifests and no chapters.
        //
        // So the count comes from the file names, which are the format's own fixed contract: a group is a
        // folder holding a {@code group.json}, a chapter holds a {@code chapter.json}, and every other
        // {@code .json} inside a chapter folder is one quest. Counting those is not a re-implementation of
        // the loader -- it is the loader's stated naming rules, and the loader is what this checks. What
        // it catches is the failure that matters: a file that was seeded, sits on disk, and never reached
        // the index.
        int seededGroups = 0;
        int seededChapters = 0;
        int seededQuests = 0;
        int seededFlat = 0;
        for (String name : examples) {
            Path relative = Path.of(name);
            String fileName = relative.getFileName().toString();

            if (fileName.equals(QuestFiles.GROUP_MANIFEST)) {
                seededGroups++;
            }
            else if (fileName.equals(QuestFiles.CHAPTER_MANIFEST)) {
                seededChapters++;
            }
            else if (fileName.endsWith(".json")) {
                if (relative.getNameCount() > 1) {
                    seededQuests++;
                }
                else {
                    // A version-1 file at the quest root: one whole tree in one document. None of the
                    // examples is one any more, which is what the assertion below says -- and counting
                    // them separately matters, because a conversion that left one behind would otherwise
                    // inflate the quest count while every id in the pair was reported as a duplicate.
                    seededFlat++;
                }
            }
        }

        assertEquals(0, seededFlat,
                "the examples still contain a version-1 flat file at the quest root. Leaving one beside"
                        + " the folder it was converted into is worse than not converting at all: every id"
                        + " then exists twice, and the loader reports a duplicate for every group, chapter"
                        + " and quest in the pair -- which reads as a broken conversion rather than as a"
                        + " stale copy.");

        // Every seeded group, chapter and quest was found -- which is the property the three
        // file-counting variants were all circling, and the only one that stays meaningful as content is
        // added.
        assertEquals(seededGroups, TaskedQuests.index().groupCount(),
                "every chapter group in the seeded files should be in the index");
        assertEquals(seededChapters, TaskedQuests.index().chapterCount(),
                "every chapter in the seeded files should be in the index");
        assertEquals(seededQuests, TaskedQuests.index().questCount(),
                "every quest in the seeded files should be in the index");

        // And not vacuously: every count above is zero if the walk found nothing, and zero equals zero.
        assertTrue(seededQuests > 0 && seededGroups > 0,
                "the counting walk found no content at all under " + EXAMPLES.toAbsolutePath()
                        + ", so every assertion above is comparing zero with zero");

        note("the seeded examples are " + seededQuests + " quest(s) in " + seededChapters
                + " chapter(s) in " + seededGroups + " group(s), from " + examples.size() + " file(s)");
    }

    @Test
    @Order(2)
    @DisplayName("a quest behind the whole chain cannot be forced, and forcing it changes nothing")
    void aLockedQuestCannotBeForced() {
        HeadlessServer.Outcome attempt = asOperator("/tasked complete the_underground");

        assertRefused(attempt, "the_underground depends on stone_tools, which depends on make_a_table");
        assertEquals(QuestState.LOCKED, stateOf("the_underground"), "and it is still locked");
        assertEquals(0, storedQuestCount(),
                "a refused command must not have written progress. A non-zero count here means the "
                        + "refusal happened after something was already recorded.");
    }

    @Test
    @Order(3)
    @DisplayName("an item task that does not consume has no button, so it cannot be handed in")
    void anItemTaskThatNeverConsumesCannotBeHandedIn() {
        HeadlessServer.Outcome attempt = asOperator("/tasked submit punch_a_tree 0");

        assertRefused(attempt, "the task completes by itself the moment the player has the logs; "
                + "there is deliberately nothing to press");
        assertEquals(QuestState.UNLOCKED, stateOf("punch_a_tree"),
                "and the quest is untouched -- unlocked, having done nothing");
        assertEquals(0, storedQuestCount(), "and nothing was written");
    }

    @Test
    @Order(4)
    @DisplayName("a checkmark cannot be handed in for a quest that is still locked")
    void aCheckmarkCannotBeHandedInWhileItsQuestIsLocked() {
        HeadlessServer.Outcome attempt = asOperator("/tasked submit read_the_sign 0");

        assertRefused(attempt, "read_the_sign depends on punch_a_tree, which is not done");
        assertEquals(QuestState.LOCKED, stateOf("read_the_sign"), "and it is still locked");
    }

    @Test
    @Order(5)
    @DisplayName("gathering the items completes the quest by itself, and pays out")
    void gatheringTheItemsCompletesTheFirstQuestAndGivesItsReward() {
        HeadlessServer.Outcome given = asOperator("/give @s minecraft:oak_log 8");
        assertEquals(1, given.result(), () -> "/give should have worked. It said:\n" + given.text());
        assertEquals(8, countInInventory(Items.OAK_LOG), "the player should be holding eight logs");

        boolean completed = tickUntil(() -> stateOf("punch_a_tree") == QuestState.COMPLETED,
                Duration.ofSeconds(20));

        assertTrue(completed, () -> "eight oak logs did not complete punch_a_tree within twenty seconds."
                + "\n  the quest is " + stateOf("punch_a_tree")
                + " and task 0 is recorded at " + recordedTask("punch_a_tree", 0) + " of 8."
                + "\n  A recorded value of 0 means the engine's auto-submit pass never ran at all,"
                + "\n  which is a different fault from running and counting wrong.");

        // Nothing is handed over yet, and this is the assertion that pins manual claiming: finishing a
        // quest and collecting its reward are two acts. The axe arrives when the player asks for it.
        assertEquals(0, countInInventory(Items.WOODEN_AXE),
                "punch_a_tree is finished, and its reward is deliberately NOT in the inventory yet -- "
                        + "a completed quest and a paid one are different states, and this is the one "
                        + "that says so");
        assertEquals(8, countInInventory(Items.OAK_LOG),
                "the task does not consume, so the logs are still there");

        HeadlessServer.Outcome claimed = asOperator("/tasked claim punch_a_tree");
        assertEquals(1, claimed.result(),
                () -> "/tasked claim should have handed the axe over. It said:\n" + claimed.text());
        assertEquals(1, countInInventory(Items.WOODEN_AXE),
                "punch_a_tree's reward is one wooden axe, and now it has been collected");

        // Clicking twice must be harmless. This is the whole reason claiming is safe to expose as a
        // button: the server re-checks rather than trusting the request.
        HeadlessServer.Outcome again = asOperator("/tasked claim punch_a_tree");
        assertRefused(again, "those rewards have already been collected once");
        assertEquals(1, countInInventory(Items.WOODEN_AXE),
                "and a second claim must not produce a second axe");

        // And the engine told somebody. This is the assertion that would have caught the bug a player
        // reported as "it doesn't register logs I have in my inventory": everything above passed while
        // the quest book showed 0/8, because the engine's automatic half had no way to reach a client.
        // The counting was right the whole time; the *reporting* was missing.
        //
        // Asserted here rather than in a test of its own because this is the moment a change happens,
        // and the property is "a change is reported when it happens". If `tick` ever goes back to
        // returning nothing, the compiler is the only thing that would object -- and only at the call
        // site in Tasked.listen, where a missing sync has no symptom at all.
        assertTrue(changedEver.contains(ownerOf(player)),
                () -> "the engine completed a quest and reported no team as changed, so nothing would "
                        + "have been sent to the client and the quest book would still say 0/8."
                        + "\n  the owner is " + ownerOf(player)
                        + " and ticks have reported " + changedEver);
    }

    @Test
    @Order(6)
    @DisplayName("a checkmark is handed in by hand, and that is how its quest completes")
    void aCheckmarkIsHandedInByHand() {
        assertEquals(QuestState.UNLOCKED, stateOf("read_the_sign"),
                "punch_a_tree completed, so its dependent should be unlocked");

        HeadlessServer.Outcome submitted = asOperator("/tasked submit read_the_sign 0");
        assertEquals(1, submitted.result(),
                () -> "the checkmark should have been accepted. It said:\n" + submitted.text());
        assertEquals(QuestState.COMPLETED, stateOf("read_the_sign"),
                "one task, submitted, is a complete quest -- nothing else observes a checkmark");
    }

    @Test
    @Order(7)
    @DisplayName("the rest of the chain completes by command, in order")
    void theRestOfTheChainCompletesByCommand() {
        for (String id : List.of("make_a_table", "stone_tools", "the_underground")) {
            QuestState before = stateOf(id);
            assertTrue(before.isPlayable(), () -> id + " should be playable by now, but it is " + before);

            HeadlessServer.Outcome done = asOperator("/tasked complete " + id);
            assertEquals(1, done.result(), () -> "/tasked complete " + id + " was refused:\n" + done.text());
            assertEquals(QuestState.COMPLETED, stateOf(id), id + " should now be completed");
        }
    }

    @Test
    @Order(8)
    @DisplayName("the whole questline reads as done")
    void theWholeQuestlineIsCompleted() {
        for (String id : THE_QUESTLINE) {
            assertEquals(QuestState.COMPLETED, stateOf(id), id + " should be completed");
        }

        // The listing is what a player without the GUI has, so it has to say something. Its wording is
        // not asserted -- see the class comment on translation keys.
        HeadlessServer.Outcome listing = asOperator("/tasked progress");
        assertTrue(listing.output().size() > 0,
                "the progress listing should print at least its header, and printed nothing at all");
    }

    @Test
    @Order(9)
    @DisplayName("resetting one quest clears exactly that quest")
    void resettingOneQuestClearsIt() {
        HeadlessServer.Outcome reset = asOperator("/tasked reset make_a_table");
        assertEquals(1, reset.result(),
                () -> "resetting one quest should clear exactly one. It said:\n" + reset.text());

        assertEquals(QuestState.UNLOCKED, stateOf("make_a_table"),
                "a cleared quest is recomputed from its dependencies, and punch_a_tree is still done, "
                        + "so it is unlocked rather than locked");
        assertEquals(QuestState.COMPLETED, stateOf("punch_a_tree"),
                "clearing one quest must not clear anything else");

        // Deliberately not asserted: whether a quest that had been completed *and* depends on the one
        // just cleared locks again. That is a design question -- honour the stored completion, or
        // recompute the whole graph -- and not a rule this test should invent an answer to. It is
        // recorded here instead, so the answer is visible rather than assumed.
        note("after resetting make_a_table: stone_tools=" + stateOf("stone_tools")
                + " the_underground=" + stateOf("the_underground")
                + " (recorded, not asserted -- see the comment)");
    }

    @Test
    @Order(10)
    @DisplayName("forcing a quest takes operator permission")
    void forcingAQuestTakesOperatorPermission() {
        // make_a_table was cleared above, so it is playable and a success here would be visible.
        HeadlessServer.Outcome asPlayer = server.run(player, 0, "/tasked complete make_a_table");
        note(asPlayer.report());

        assertTrue(asPlayer.refused(), "a player without op must not be able to force a quest");
        assertNotNull(asPlayer.thrown(),
                "it should be Brigadier refusing the command -- unknown or unpermitted -- so Tasked's "
                        + "code never runs. A refusal from inside Tasked would mean the permission gate "
                        + "is in the wrong place.");
        assertNotEquals(QuestState.COMPLETED, stateOf("make_a_table"),
                "and nothing may have changed");

        HeadlessServer.Outcome asOperator = asOperator("/tasked complete make_a_table");
        assertEquals(1, asOperator.result(), () -> "an operator should be able to. It said:\n" + asOperator.text());
        assertEquals(QuestState.COMPLETED, stateOf("make_a_table"), "and now it is done");
    }

    // ------------------------------------------------------------------
    // Two players, which is Stage 3's second half
    // ------------------------------------------------------------------

    /**
     * <h2>What was actually wrong here</h2>
     *
     * <p>The next three tests are the plan's second Stage 3 clause — <i>"two players on a dedicated
     * server share progress correctly"</i> — and writing them found a real defect rather than
     * confirming a design.
     *
     * <p>{@code ProgressService} keys progress by <b>team id</b>, and its class comment said sharing
     * therefore "falls out rather than needing to be built". That is true of the <i>storage</i> and it
     * was false of the <i>counting</i>, which is the half nobody looks at. {@code tick} chose one
     * member per team, by player-list order — whoever logged in first — and {@code evaluateTeam}
     * counted item tasks in that one member's inventory. The parameter was even named
     * {@code anyMember}, which is the tell: for a task that asks whether <i>somebody</i> in the party
     * has eight logs, who you ask looks like it should not matter.
     *
     * <p>It matters completely, and it is wrong in exactly the case parties exist for. One of you
     * gathers the wood, and the wood is in <i>their</i> inventory. Ask the other one and the party
     * sees nothing — and the failure presents as "the quest never completes", which sends you to the
     * quest file rather than to the loop.
     *
     * <h2>Why the tests alternate who holds the items</h2>
     *
     * <p>This is the design that makes them worth having, and it is deliberate rather than tidy.
     * Giving the items to the non-owner is a stronger test than giving them to the owner, because the
     * old bug chose by player-list order and the owner tends to be first. But a fixed "always give
     * them to the friend" would still pass if somebody later picked a representative by a
     * <i>different</i> fixed rule — say the last member, or the one with the highest UUID.
     *
     * <p>So the direction reverses: {@link #aPartySharesOneProgressRecord} has the friend gather, and
     * {@link #theOtherPlayerGatheringWorksToo} has the owner gather. <b>No single chosen member can
     * pass both.</b> That is the whole reason there are two of them.
     */

    @Test
    @Order(11)
    @DisplayName("a party shares one progress record, and one player gathering works for both")
    void aPartySharesOneProgressRecord() {
        friend = server.spawnPlayer("tasked-friend");
        note("spawned '" + friend.getScoreboardName() + "', a second real player on the server");

        // Emptied now that both players exist, and this matters more than it looks. The solo run
        // above left player holding eight oak logs, because punch_a_tree's task does not consume --
        // so without this, both members would be holding exactly the required eight and the test
        // could pass by accident through the *other* member, which is the bug it exists to catch.
        clearInventories();

        // Created and joined through Armature's own API, which is what /tasked party will wrap. The
        // invite is a separate step from the accept because they fire different events, and accepting
        // is what fires MEMBER_JOINED.
        partyId = server.callOnServerThread(() -> {
            var teams = Teams.of(server.server());
            var party = teams.create("the playthrough party", player.getUUID());
            boolean invited = teams.invite(party.id(), friend.getUUID());
            assertTrue(invited, "the friend should have been invited to the new party");
            return party.id();
        });

        Optional<UUID> joined = server.callOnServerThread(() ->
                Teams.of(server.server()).acceptInvite(friend.getUUID()).map(team -> team.id()));
        assertEquals(Optional.of(partyId), joined, "the friend should now be in the party");

        assertEquals(partyId, ownerOf(player), "the party's own id is the key progress is stored under");
        assertEquals(ownerOf(player), ownerOf(friend),
                "and both members resolve to it -- which is the whole of 'progress is shared'");
        assertNotEquals(player.getUUID(), ownerOf(player),
                "and it is neither player's solo id, so the party starts empty rather than inheriting "
                        + "what its owner did alone");

        // The friend holds them, and only the friend. Giving them to the member that a first-in-the-
        // player-list rule would *not* pick is what makes this fail loudly if that rule comes back.
        server.onServerThread(() -> {
            friend.getInventory().add(new ItemStack(Items.OAK_LOG, 8));
            friend.getInventory().setChanged();
        });

        assertEquals(0, countInInventoryOf(player, Items.OAK_LOG), "the party's owner is holding none");
        assertEquals(8, countInInventoryOf(friend, Items.OAK_LOG), "and the friend is holding eight");

        boolean completed = tickUntil(() -> stateFor(friend, "punch_a_tree") == QuestState.COMPLETED,
                Duration.ofSeconds(20));

        assertTrue(completed, () -> "eight logs in the *friend's* inventory did not complete the party's "
                + "punch_a_tree within twenty seconds, though the owner is holding none."
                + "\n  This is the party bug: the engine counted in one member's inventory, chosen by "
                + "player-list order, instead of in every member's."
                + "\n  the friend's task 0 is recorded at " + recordedTaskFor(friend, "punch_a_tree", 0)
                + " and the owner's resolution says " + stateFor(player, "punch_a_tree"));

        assertEquals(QuestState.COMPLETED, stateFor(player, "punch_a_tree"),
                "read from the owner, the same quest is complete -- there is one record, so there is "
                        + "nothing for the two of them to disagree about");

        // Collected by the member who did NOT gather, and deliberately.
        //
        // The payout belongs to the party's *record*, not to the inventory that happened to satisfy it,
        // so either member can collect it -- and now that claiming is separate from completing, "who
        // gets it" has the only honest answer: whoever asks. This test asks from the side a "pay the
        // gatherer" shortcut would get wrong, which is what makes it worth asserting rather than the
        // other way round.
        assertEquals(0, countInInventoryOf(friend, Items.WOODEN_AXE),
                "nothing is handed over at completion, to either member");
        assertEquals(0, countInInventoryOf(player, Items.WOODEN_AXE), "to either of them");

        HeadlessServer.Outcome claimed = asOperator("/tasked claim punch_a_tree");
        assertEquals(1, claimed.result(),
                () -> "a party's finished quest should be collectable by either member. It said:\n"
                        + claimed.text());
        assertEquals(1, countInInventoryOf(player, Items.WOODEN_AXE),
                "the member who asked receives the reward");
        assertEquals(0, countInInventoryOf(friend, Items.WOODEN_AXE),
                "and the member who gathered does not -- one payout, to whoever collected it");
    }

    @Test
    @Order(12)
    @DisplayName("and with the other player gathering, which no single chosen member could pass twice")
    void theOtherPlayerGatheringWorksToo() {
        clearInventories();

        server.onServerThread(() -> {
            player.getInventory().add(new ItemStack(Items.CRAFTING_TABLE, 1));
            player.getInventory().setChanged();
        });

        // make_a_table is the next quest with an item task, and it depends on punch_a_tree -- which
        // the party completed in the previous test, so this also checks that a party's completion
        // unlocks the next quest for the party rather than for one of its members.
        boolean completed = tickUntil(() -> stateFor(player, "make_a_table") == QuestState.COMPLETED,
                Duration.ofSeconds(20));

        assertTrue(completed, () -> "the owner holding a crafting table did not complete the party's "
                + "make_a_table. punch_a_tree is " + stateFor(player, "punch_a_tree")
                + " and make_a_table is " + stateFor(player, "make_a_table"));

        assertEquals(QuestState.COMPLETED, stateFor(friend, "make_a_table"),
                "and the other member sees it too");

        // And the other way round again: the friend collects this time, so between these two tests the
        // gatherer *and* the collector both reverse. Neither "pay whoever gathered" nor "pay one chosen
        // member" can pass both.
        HeadlessServer.Outcome claimed = asOperator(friend, "/tasked claim make_a_table");
        assertEquals(1, claimed.result(),
                () -> "the owner gathered the crafting table, so the friend should still be able to "
                        + "collect the payout. It said:\n" + claimed.text());
        assertEquals(8, countInInventoryOf(friend, Items.STICK),
                "the friend asked, so the friend received");
        assertEquals(0, countInInventoryOf(player, Items.STICK),
                "and the member who gathered does not");
    }

    @Test
    @Order(13)
    @DisplayName("leaving takes your own progress with you, and the party keeps the party's")
    void leavingDoesNotMoveProgress() {
        boolean left = server.callOnServerThread(() -> Teams.of(server.server()).leave(friend.getUUID()));
        assertTrue(left, "the friend was in a real party, so leaving should have done something");

        assertEquals(friend.getUUID(), ownerOf(friend),
                "an unpartied player's progress is keyed by their own id again");
        assertEquals(partyId, ownerOf(player), "and the party is unaffected by somebody leaving it");

        assertEquals(QuestState.COMPLETED, stateFor(player, "punch_a_tree"),
                "the party keeps what it did together");
        assertNotEquals(QuestState.COMPLETED, stateFor(friend, "punch_a_tree"),
                "and the friend does not, because their own progress is their own and they never did "
                        + "this alone. ProgressStore documents that as deliberate: merging on join would "
                        + "hand a party whatever a new member had already done, up to and including a "
                        + "finished questline.");

        note("after " + friend.getScoreboardName() + " left: the party sees punch_a_tree="
                + stateFor(player, "punch_a_tree") + ", and their own progress says "
                + stateFor(friend, "punch_a_tree"));
    }

    @Test
    @Order(14)
    @DisplayName("a player who partied and then left still has everything they did alone")
    void soloProgressSurvivesAParty() {
        // The friend left in the previous test, so the party is now the owner on their own -- and
        // Armature removes a team once its last member leaves rather than leaving an empty one.
        boolean left = server.callOnServerThread(() -> Teams.of(server.server()).leave(player.getUUID()));
        assertTrue(left, "the owner was in a party, so leaving should have done something");

        assertEquals(player.getUUID(), ownerOf(player),
                "so this player is solo again, keyed by their own id");

        assertEquals(QuestState.COMPLETED, stateFor(player, "punch_a_tree"),
                "and everything from the solo playthrough in orders 5 to 10 is still there -- the "
                        + "party never touched it, in either direction");
        assertEquals(QuestState.COMPLETED, stateFor(player, "the_underground"),
                "all the way to the end of the questline");

        note("after the owner left: solo progress is intact, punch_a_tree="
                + stateFor(player, "punch_a_tree") + " the_underground=" + stateFor(player, "the_underground"));
    }

    @Test
    @Order(15)
    @DisplayName("and here is what that does not prove, said plainly rather than implied")
    void whatThePartyTestsDoNotProve() {
        // Not an assertion about Tasked; an assertion that a claim is not being overclaimed. The
        // harness's own server reports isDedicatedServer() false, because it subclasses
        // MinecraftServer rather than DedicatedServer -- see HeadlessServer for why. So "two players
        // on a dedicated server share progress" is verified for every part Tasked owns and not for
        // the loader's dedicated-server bootstrap.
        //
        // Checked rather than assumed: `isDedicatedServer` appears in exactly one file under
        // tasked/common/src/main, and it is HeadlessServer's own override. No Tasked code branches on
        // it, so there is no dedicated-server-only path here that this test is skipping.
        boolean dedicated = server.callOnServerThread(() -> server.server().isDedicatedServer());
        assertFalse(dedicated,
                "if this ever becomes true, the note above is stale and the harness has changed shape");

        note("NOT PROVEN by these tests: that the same progress-sharing holds when the server is a "
                + "real dedicated server with a real client attached. Every part Tasked owns is "
                + "covered, and nothing in Tasked branches on isDedicatedServer -- but the loader's "
                + "dedicated bootstrap and the client's packet decode are not exercised here. That "
                + "needs a real client, and it is the one item on the list that does.");
    }

    @Test
    @Order(16)
    @DisplayName("a tick that finds nothing new reports nothing, so the sync is not a packet per tick")
    void anIdleTickReportsNothing() {
        // The other half of the fix, and the half that makes it affordable. The engine runs on a player
        // tick -- twenty times a second, per player -- so "tell the clients when it moves" is only
        // acceptable because it almost never moves. If `tick` reported a change every time it was
        // called, this fix would be a progress packet per player per tick, forever, and nobody would
        // see it in a test that only checked the bits that do change.
        //
        // Everything above is settled by now: the questline is complete, the player has left the party,
        // and nothing in any inventory is going to move again. So a tick eventually reports nothing --
        // "eventually" rather than "immediately", because the state before this test is whatever the
        // tests above left, and one of them reset a quest that a tick may legitimately re-complete.
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        boolean idle = false;
        while (System.nanoTime() < deadline) {
            tickOnce();
            if (lastTick.isEmpty()) {
                idle = true;
                break;
            }
            sleep(100);
        }

        assertTrue(idle, () -> "fifteen seconds of ticks and every one reported a change, so every one "
                + "would have sent a progress packet to every member of the team. The last reported "
                + lastTick + " -- a change that repeats forever is a value being written per tick "
                + "rather than a quest being completed.");

        note("an idle tick reports nothing: the engine's own output is empty, so the sync it drives "
                + "costs nothing on the ticks where nothing happened");
    }

    // ------------------------------------------------------------------
    // The two pushes the tick cannot make
    // ------------------------------------------------------------------

    /**
     * <h2>Why these are separate from the tick's push, which is already covered</h2>
     *
     * <p>Order 5 asserts that the engine's automatic push happens — a tick completes a quest and
     * reports the team as changed, which is what fixed "the quest book still says 0/8". That push goes
     * through {@code ProgressService.tick}, and the two tests below are about the changes a tick
     * <b>cannot</b> see at all. Both were half-built in the committed code and neither had a test:
     *
     * <ol>
     *   <li><b>A command.</b> {@code evaluateTeam} skips every quest that is not playable, so a quest
     *       that is already COMPLETED is never reported as changed again, however often it is ticked.
     *       {@code /tasked reset} and {@code /tasked claim} both move something a client is showing and
     *       are both invisible to the tick. {@code /tasked complete} likewise.</li>
     *   <li><b>A team change.</b> {@code REASON_TEAM_CHANGED} existed, {@code sendProgress} implemented
     *       the full-sync-on-team-change branch, and nothing ever sent one — the two listeners on
     *       Armature's team events logged and returned.</li>
     * </ol>
     *
     * <p>Both are asserted by counting messages, not by inspecting the wire, and the reason is stated
     * where the counter lives: a loader is absent here, so "did a message get produced" is the only
     * question a test can ask, and counting at the one place a message is produced means a route
     * nobody thought of is counted too.
     */

    @Test
    @Order(17)
    @DisplayName("a command that moves progress pushes it, which the tick could never have done for it")
    void aCommandPushesTheChangeTheTickCannotSee() {
        // the_underground was completed back in order 7, so before this it is COMPLETED -- which is
        // exactly the state `evaluateTeam` skips, so no amount of ticking would ever report it again.
        assertEquals(QuestState.COMPLETED, stateOf("the_underground"),
                "this test is about a change the tick cannot see, so it has to start from the state "
                        + "that makes it invisible: an already-completed quest");

        int before = messagesSent(ProgressSyncPayload.REASON_CHANGED);
        int beforeToPlayer = messagesSentTo(ProgressSyncPayload.REASON_CHANGED, player);

        HeadlessServer.Outcome reset = asOperator("/tasked reset the_underground");
        assertEquals(1, reset.result(),
                () -> "/tasked reset the_underground should have cleared one quest. It said:\n"
                        + reset.text());

        assertTrue(stateOf("the_underground").isPlayable(),
                "and the reset moved it out of COMPLETED, which is the whole point: the state it was "
                        + "in produces no tick, and the state it is in now produces one. So the client "
                        + "had to be told by the command, or it keeps drawing a finished quest");

        assertEquals(before + 1, messagesSent(ProgressSyncPayload.REASON_CHANGED),
                "resetting a quest should have produced exactly one progress message. Zero means the "
                        + "command changed something a client is drawing and told nobody, and the tick "
                        + "cannot make up the difference -- see this test's note on why");
        assertEquals(beforeToPlayer + 1,
                messagesSentTo(ProgressSyncPayload.REASON_CHANGED, player),
                "and it reaches the player who ran the command, which is the sender's own team");

        // The half that keeps this from becoming "one message per command, whether or not anything
        // happened". A refusal returns before the push, so a command that changed nothing is silent --
        // and this counts every reason rather than just REASON_CHANGED, because "silent" has to mean
        // no message at all. Counting one reason would pass while a push under another reason happened.
        int after = messagesSent();
        HeadlessServer.Outcome refused = asOperator("/tasked reset no_such_quest_at_all");
        assertRefused(refused, "there is no quest by that name, so nothing was cleared");
        assertEquals(after, messagesSent(),
                "a command that changed nothing must push nothing, for any reason. A message here means "
                        + "the push happens before the command knows whether it did anything, which turns "
                        + "every mistyped id into a progress packet to the whole party");
    }

    @Test
    @Order(18)
    @DisplayName("both sides of a party change are told, with the reason that forces a full sync")
    void aPartyChangeIsPushedToBothSidesOfIt() {
        // Driven by hand, and this is a real limitation stated rather than glossed. Tasked's listeners
        // on Armature's team events are registered from `Tasked.listenToTeams`, which is hooked to
        // SERVER_STARTED -- and that event is fired by loader code in each loader's subproject, so no
        // test JVM ever fires it. `Tasked`'s own note on that guard says as much: "nothing in Tasked's
        // tests can come to depend on a team event having been subscribed, because it never is there".
        //
        // So the team events fired by the Teams calls above reach no listener at all, and the push
        // those listeners perform is exercised by calling the method they call with the same arguments
        // they pass. What is left unasserted is that the *subscription* happened, which nothing in this
        // repo can reach -- the same shape of gap as the loader's transport.
        UUID secondParty = server.callOnServerThread(() -> {
            var teams = Teams.of(server.server());
            var party = teams.create("the second playthrough party", player.getUUID());
            assertTrue(teams.invite(party.id(), friend.getUUID()),
                    "the friend should have been invited to the second party");
            return party.id();
        });

        assertEquals(Optional.of(secondParty),
                server.callOnServerThread(() -> Teams.of(server.server())
                        .acceptInvite(friend.getUUID()).map(team -> team.id())),
                "and is now in it, so there are two members for a change to be news to");

        assertTrue(server.callOnServerThread(() -> Teams.of(server.server()).leave(friend.getUUID())),
                "leaving a party they were in should have done something");

        assertEquals(friend.getUUID(), ownerOf(friend),
                "and their progress is keyed by their own id again -- which is the whole reason the "
                        + "leaver has to be named separately. MEMBER_LEFT carries the team as it now is, "
                        + "and that team no longer contains them, so walking it would tell everybody "
                        + "except the one player whose progress actually moved");

        assertEquals(0, messagesSentTo(ProgressSyncPayload.REASON_TEAM_CHANGED, player),
                "nothing above pushed a team-change message: the events fired with no listener "
                        + "attached, so every message counted below is this test's own doing and the "
                        + "assertions are not passing on somebody else's traffic");
        assertEquals(0, messagesSentTo(ProgressSyncPayload.REASON_TEAM_CHANGED, friend),
                "and the same for the other member");

        int before = messagesSent(ProgressSyncPayload.REASON_TEAM_CHANGED);

        // The same call `Tasked.listenToTeams`'s MEMBER_LEFT listener makes: the team, and the player
        // who has left it.
        server.callOnServerThread(() -> {
            Teams.of(server.server()).byId(secondParty).ifPresent(team ->
                    TaskedNetworking.sendTeamChange(server.server(), team, friend.getUUID()));
            return null;
        });

        assertEquals(before + 2, messagesSent(ProgressSyncPayload.REASON_TEAM_CHANGED),
                "one team change should produce one message per person who has to hear it, so two for "
                        + "a party of two. Four means both members were told twice -- which is what a "
                        + "disband does if the listener walks the team, since each member's own "
                        + "MEMBER_LEFT carries the whole team");
        assertEquals(1, messagesSentTo(ProgressSyncPayload.REASON_TEAM_CHANGED, player),
                "the member still in the party is told, because the team's progress is theirs and the "
                        + "leaver arriving with their own share is what can change it");
        assertEquals(1, messagesSentTo(ProgressSyncPayload.REASON_TEAM_CHANGED, friend),
                "and the player who left is told exactly once. **If this count is 0, the leaver is the "
                        + "one person a team-only push can never reach** -- which is the bug this "
                        + "parameter exists to prevent, and it is the worst possible shape for it: "
                        + "everybody still in the party gets corrected, and the player whose screen is "
                        + "now showing the wrong team's questline is the one who never hears");

        // Note what is deliberately not asserted: that the message was a *full* sync. The reason code
        // is on the wire and `QuestSync.sendProgress` branches on it, but whether the bytes were a full
        // set or a delta is only visible through `chunk.full()`, and this test has no client to decode
        // it. `QuestSyncTest` covers the branch itself; this covers that it is reached with the right
        // reason, which is the half that was broken.
        note("a team change pushed " + messagesSent(ProgressSyncPayload.REASON_TEAM_CHANGED)
                + " message(s) for a party of two, one to each member, tagged "
                + "REASON_TEAM_CHANGED so the client is sent the whole questline rather than a delta "
                + "against the team it has just stopped being in");
    }

    // ------------------------------------------------------------------
    // T4's other half: /tasked party, and the modes it sets
    // ------------------------------------------------------------------

    /**
     * <h2>Why the party is built by command here when orders 11 to 14 built one through the API</h2>
     *
     * <p>Because those two are not the same test, and the difference is the whole of what this round
     * added. Orders 11 to 14 drive {@code Teams.of(server)} directly, which proves the <i>engine</i>
     * shares progress across a team. Everything below goes through {@code /tasked party}, which is a
     * different set of code on top of the same API — and the parts of it that can be wrong are the
     * parts a command adds: a permission question answered the wrong way, an argument read under the
     * wrong name, a mode written to the wrong team's key.
     *
     * <h2>What is deliberately not asserted, and it is the same gap as the team listeners</h2>
     *
     * <p>Nothing here counts the messages a party command produces. It cannot: the stored source
     * answers {@code firesEvents()} true, so {@code pushMembershipChange} correctly declines to push
     * and leaves it to the event — and those listeners are registered from {@code SERVER_STARTED},
     * which no test JVM fires. See order 18. So a party built by command here tells nobody anything,
     * and that is the harness's shape rather than a fault in the command.
     *
     * <p>What <i>is</i> asserted is everything the command is responsible for that has a state to
     * read back: the party exists, the player's progress moved onto it, and the mode reached the store
     * and then the engine.
     */

    @Test
    @Order(19)
    @DisplayName("/tasked party creates a real party, and moves the creator's progress onto it")
    void thePartyCommandCreatesARealParty() {
        // Out of whatever party a previous test left this player in, and *through the command*, so that
        // `leave` is exercised by the same route a player uses rather than only through Armature's API.
        //
        // This line is load-bearing and was missing on the first run, which failed here with an owner
        // id that was neither this player's nor anything the test had made: order 18 creates a party to
        // prove the team-change push and never disbands it, so "starts from a solo player" was simply
        // false. The fix is not to assume the state but to establish it -- and establishing it through
        // `leave` covers a subcommand that nothing else does.
        HeadlessServer.Outcome leave = asOperator("/tasked party leave");
        assertEquals(1, leave.result(),
                () -> "this player should have been in a party to leave. It said:\n" + leave.text());

        assertEquals(player.getUUID(), ownerOf(player),
                "so this player is solo again, and their progress is keyed by their own id");

        HeadlessServer.Outcome created = asOperator("/tasked party create the-command-party");
        assertEquals(1, created.result(),
                () -> "/tasked party create should have formed a party. It said:\n" + created.text());

        // The assertion that matters, and it is the one a command can get wrong in a way nothing else
        // notices: the creator's *progress owner* has to have moved. A party that exists while its
        // owner's progress stays keyed to their solo id is a party whose questline is invisible to
        // everybody in it, and every line of output above would still look right.
        commandPartyId = ownerOf(player);
        assertNotEquals(player.getUUID(), commandPartyId,
                "the creator's progress must now be keyed by the party, not by themselves -- otherwise "
                        + "they are in a party whose progress nobody in it can see");

        assertTrue(server.callOnServerThread(() -> Teams.of(server.server())
                        .realTeamOf(player.getUUID()).isPresent()),
                "and a real team exists for them, rather than a synthesised solo one");

        // Fresh, so the party starts empty rather than inheriting the four quests this player has
        // already completed alone. That is ProgressStore's deliberate rule and worth re-checking here,
        // because a command that passed the wrong id would look exactly like a party that inherited.
        assertNotEquals(QuestState.COMPLETED, stateOf("punch_a_tree"),
                "a new party's progress is empty, whatever its creator did before it");

        HeadlessServer.Outcome again = asOperator("/tasked party create another-one");
        assertRefused(again, "the player is already in a party");
        assertEquals(commandPartyId, ownerOf(player), "and the refusal changed nothing");

        HeadlessServer.Outcome info = asOperator("/tasked party");
        assertEquals(1, info.result(), () -> "/tasked party should report. It said:\n" + info.text());

        // Read from the manager rather than from the command's output, and that is the rule this file
        // states at the top: a dedicated server has no language file, so `getString()` on a translatable
        // returns the *key* -- which is why nothing here asserts on output text. An earlier version of
        // this line checked that the listing contained the word "stored", which could never have passed
        // whatever the command did.
        assertEquals("stored", server.callOnServerThread(() -> Teams.of(server.server()).name()),
                "the listing's source line is about naming which mod provides these parties, and this "
                        + "is the answer it reports -- read here rather than from the rendered string");

        note("the party command formed a real party; progress is now keyed by " + commandPartyId
                + " rather than by " + player.getUUID());
    }

    @Test
    @Order(20)
    @DisplayName("a party's progress mode is set by command, and read back from the store")
    void theModeIsSetByCommand() {
        assertEquals(PartyMode.DEFAULT, modeOf(player),
                "a new party counts the default way, which is what the engine did before there was a "
                        + "choice -- so no file and no command has to exist for sharing to work");

        HeadlessServer.Outcome read = asOperator("/tasked party mode");
        assertEquals(1, read.result(), () -> "/tasked party mode should report. It said:\n" + read.text());

        HeadlessServer.Outcome bad = asOperator("/tasked party mode nonsense");
        assertRefused(bad, "there is no such mode");
        assertEquals(PartyMode.DEFAULT, modeOf(player),
                "and a refused mode writes nothing -- a typo must not leave a party counting by a rule "
                        + "nobody chose");

        // A window, so the counts below are this command's and not the fixture's traffic.
        server.callOnServerThread(() -> {
            TaskedNetworking.forgetRosters();
            return null;
        });

        HeadlessServer.Outcome set = asOperator("/tasked party mode pooled");
        assertEquals(1, set.result(),
                () -> "/tasked party mode pooled should have been accepted. It said:\n" + set.text());
        assertEquals(PartyMode.POOLED, modeOf(player), "and the store has it");

        // The party is told, because the panel draws the rule as a line of text rather than as a button
        // a player presses and watches. Without the push the row would keep reading the old rule until
        // something else moved a member -- which is exactly how the cycling button read as doing
        // nothing, one round ago.
        assertEquals(1, server.callOnServerThread(() -> TaskedNetworking.rostersSentTo(player.getUUID())),
                "setting the mode should have pushed the roster that carries it");

        // Setting it again is a no-op rather than a change, so it reports 0 -- the same convention
        // every other command here follows, where the return value is the assertion.
        HeadlessServer.Outcome same = asOperator("/tasked party mode pooled");
        assertRefused(same, "nothing changed, so nothing happened worth reporting");
        assertEquals(PartyMode.POOLED, modeOf(player), "and it is still set");
        assertEquals(1, server.callOnServerThread(() -> TaskedNetworking.rostersSentTo(player.getUUID())),
                "and a refusal is not news, so nothing was sent for it");

        HeadlessServer.Outcome back = asOperator("/tasked party mode one_member");
        assertEquals(1, back.result(), "an explicit return to the default is a change and reports one");
        assertEquals(PartyMode.ONE_MEMBER, modeOf(player));
        assertEquals(2, server.callOnServerThread(() -> TaskedNetworking.rostersSentTo(player.getUUID())),
                "and the second real change was pushed as well as the first");

        // Stored and greppable, which is the half a party's mode has to survive: a restart re-reads
        // this file, and a reader opening it should be able to see why a party counts as it does.
        assertTrue(server.callOnServerThread(() ->
                        PartyStore.of(server.server()).has(commandPartyId)),
                "a party that has chosen is recorded, so the choice outlives the session");

        note("the mode round-tripped through the command and the store; a typo was refused and wrote "
                + "nothing");
    }

    @Test
    @Order(21)
    @DisplayName("the mode reaches the engine: one_member cannot finish what pooled can")
    void theModeChangesWhatTheEngineCounts() {
        // The whole point of the round, in one test. Everything above proves the mode is stored and
        // readable; this proves the engine applies it, which is the only reason it exists.
        //
        // Two members of one party, four oak logs each, and punch_a_tree wants eight. Under the
        // default neither member has eight, so the party has not finished it; under pooled they have
        // eight between them, so it has. The two modes disagree about the *same two inventories*, which
        // is what makes this an assertion about the mode rather than about the items.
        assertTrue(server.callOnServerThread(() -> {
                    Teams.of(server.server()).invite(commandPartyId, friend.getUUID());
                    return Teams.of(server.server()).acceptInvite(friend.getUUID()).isPresent();
                }),
                "the friend should have joined the command-created party");

        assertEquals(commandPartyId, ownerOf(friend),
                "both members resolve to the party, so there is one progress record between them");

        clearInventories();
        server.onServerThread(() -> {
            player.getInventory().add(new ItemStack(Items.OAK_LOG, 4));
            player.getInventory().setChanged();
            friend.getInventory().add(new ItemStack(Items.OAK_LOG, 4));
            friend.getInventory().setChanged();
        });

        assertEquals(4, countInInventoryOf(player, Items.OAK_LOG));
        assertEquals(4, countInInventoryOf(friend, Items.OAK_LOG));

        // Tick for a while and check it does NOT complete. Ticking first and asserting the negative
        // afterwards is the order that makes this mean something: an assertion that the quest is
        // incomplete, with no ticks in between, would pass on a party the engine had never looked at.
        tickUntil(() -> false, Duration.ofSeconds(3));

        assertEquals(PartyMode.ONE_MEMBER, modeOf(player), "this half runs under the default");
        assertEquals(QuestState.LOCKED, stateOf("the_underground"),
                "and the party's questline starts where a locked quest starts");
        assertNotEquals(QuestState.COMPLETED, stateOf("punch_a_tree"),
                () -> "four logs each is four logs a member for the default mode, and this quest wants "
                        + "eight. It completed anyway, which means the mode is being ignored and the "
                        + "members' counts are being added regardless."
                        + "\n  the owner's task 0 is recorded at "
                        + recordedTaskFor(player, "punch_a_tree", 0));

        HeadlessServer.Outcome set = asOperator("/tasked party mode pooled");
        assertEquals(1, set.result(), () -> "setting the mode should have worked:\n" + set.text());

        boolean completed = tickUntil(() -> stateFor(player, "punch_a_tree") == QuestState.COMPLETED,
                Duration.ofSeconds(20));

        assertTrue(completed, () -> "with the same four logs each, pooled mode did not complete "
                + "punch_a_tree, which needs eight. The two counts add to exactly eight, so this is "
                + "the mode not reaching the engine rather than the arithmetic being wrong -- see "
                + "PartyModeTest, which pins the arithmetic on its own."
                + "\n  the owner's task 0 is recorded at "
                + recordedTaskFor(player, "punch_a_tree", 0)
                + " and the state is " + stateOf("punch_a_tree"));

        assertEquals(QuestState.COMPLETED, stateFor(friend, "punch_a_tree"),
                "and both members see it, because there is one record and the mode decided it");

        note("four oak logs each: refused under one_member, completed under pooled. The same two "
                + "inventories, two different answers, which is the mode being applied rather than "
                + "described");

        // ---- and now the third mode, on a different quest, so the completed one is not in the way.

        HeadlessServer.Outcome ownerOnly = asOperator("/tasked party mode owner_only");
        assertEquals(1, ownerOnly.result(),
                () -> "setting owner_only should have worked:\n" + ownerOnly.text());

        clearInventories();
        server.onServerThread(() -> {
            friend.getInventory().add(new ItemStack(Items.CRAFTING_TABLE, 1));
            friend.getInventory().setChanged();
        });

        tickUntil(() -> false, Duration.ofSeconds(3));

        assertEquals(0, countInInventoryOf(player, Items.CRAFTING_TABLE),
                "the owner is holding nothing");
        assertEquals(1, countInInventoryOf(friend, Items.CRAFTING_TABLE), "and the friend is holding one");
        assertNotEquals(QuestState.COMPLETED, stateFor(player, "make_a_table"),
                () -> "owner_only counts the owner and nobody else, so the friend holding the crafting "
                        + "table must not complete make_a_table. It did, which means the mode is being "
                        + "ignored for the second quest after being honoured for the first."
                        + "\n  the owner's task 0 is recorded at "
                        + recordedTaskFor(player, "make_a_table", 0));

        server.onServerThread(() -> {
            player.getInventory().add(new ItemStack(Items.CRAFTING_TABLE, 1));
            player.getInventory().setChanged();
        });

        boolean ownerCompleted = tickUntil(
                () -> stateFor(player, "make_a_table") == QuestState.COMPLETED, Duration.ofSeconds(20));

        assertTrue(ownerCompleted, () -> "the owner holding the crafting table should complete "
                + "make_a_table under owner_only. The state is " + stateOf("make_a_table"));

        note("and under owner_only the friend's crafting table counted for nothing until the owner "
                + "held one -- so all three modes reach the engine");
    }

    @Test
    @Order(22)
    @DisplayName("a party's roster can be read back off the server, which is what the client is sent")
    void aPartysRosterIsReadBackOffTheServer() {
        // **The regression test for the fault this round is about**, and it is the whole of it in one
        // assertion. `PartySnapshot.of` asked Armature for `teamOf(teamId)` — and `teamOf` takes a
        // *player's* id, so handing it a team id found no such player, found no team, and synthesised
        // `Team.solo(teamId)`, whose `persistent` is false. The guard below that turned every real
        // party into "nobody is in a party", so `sendPartyToTeam` returned at its first line and no
        // roster was ever put on the wire. The panel was correct and was never told anything.
        //
        // So this asserts the one thing that was false: a party that really exists, looked up by its
        // own id, reads back as a party with members in it. Nothing about the client, nothing about
        // the panel -- just the lookup, because that is where the fault was and a test that went
        // through the UI would be able to fail in more places than the one being described.
        //
        // The party is order 19's, created by `/tasked party create`, and it has the friend in it from
        // order 21 — so this is a party of two real players rather than a fixture.
        assertNotNull(commandPartyId, "order 19 should have created a party for this test to read");

        PartySnapshot snapshot = server.callOnServerThread(
                () -> PartySnapshot.of(server.server(), commandPartyId));

        assertTrue(snapshot.isPresent(),
                "the party " + commandPartyId + " exists on this server and has members, so reading "
                        + "it back by its own id must not report that nobody is in it. An empty or "
                        + "absent snapshot here is the reported fault: the roster never reaches a "
                        + "client, so the party panel draws its empty state for a party that exists");

        assertEquals(commandPartyId, snapshot.teamId(),
                "and it is *this* party that was read, not a synthesised team of one keyed by the same "
                        + "id -- which is precisely what asking for a team by a player's id produces");

        assertEquals(2, snapshot.members().size(),
                "two members: the player who created it in order 19, and the friend who joined in "
                        + "order 21. One means the lookup found something other than the real team");

        assertTrue(snapshot.members().stream().anyMatch(member -> member.id().equals(player.getUUID())),
                "the creator is in the roster the server would send");
        assertTrue(snapshot.members().stream().anyMatch(member -> member.id().equals(friend.getUUID())),
                "and so is the friend, so this is the party rather than a party of one");

        // Read through the format the client actually receives, so this covers the packing as well as
        // the lookup. A snapshot that read back correctly and packed into something the client could
        // not parse would pass every assertion above and leave the panel empty anyway.
        PartySnapshot roundTripped = PartySnapshot.unpack(snapshot.pack());
        assertEquals(2, roundTripped.members().size(),
                "the roster survives the wire format the payload carries it in");

        note("the party's roster read back off the server with " + roundTripped.members().size()
                + " member(s) and survived the packed form the client is sent -- the lookup that was "
                + "returning nobody's-team for every real party now returns the party");
    }

    @Test
    @Order(23)
    @DisplayName("a disband names every member, and the roster it sends is what an open panel redraws from")
    void aDisbandTellsEveryMemberTheyAreInNoParty() {
        // **Driven by hand, for the same reason order 18 is**, and the limitation is the one stated
        // there: Tasked's listeners on Armature's team events are registered from
        // `Tasked.listenToTeams`, which hangs off SERVER_STARTED, and no loader fires that in a test
        // JVM. So `disband` below fires one MEMBER_LEFT per member into no listener at all, and the
        // push the listener's DISBANDED branch performs is exercised by calling the method it calls,
        // once per member, with the arguments that branch passes.
        //
        // What that leaves unasserted is the subscription. What it pins is the thing that was wrong:
        // the disband branch sent every member their progress and returned, so no client was ever told
        // the party was gone. That was invisible while the party panel closed on every press, and it is
        // a panel describing a party that does not exist now that the panel stays open -- see
        // `TaskedNetworking.rostersSentTo`.
        assertNotNull(commandPartyId, "orders 19-22 should have left a party of two to disband");

        List<UUID> members = server.callOnServerThread(() -> Teams.of(server.server())
                .byId(commandPartyId)
                .map(team -> List.copyOf(team.memberIds()))
                .orElse(List.of()));
        assertEquals(2, members.size(),
                "the party orders 19-21 built should have two members for a disband to be news to: "
                        + members);

        server.callOnServerThread(() -> {
            TaskedNetworking.forgetRosters();
            return null;
        });

        HeadlessServer.Outcome disbanded = asOperator("/tasked party disband");
        assertEquals(1, disbanded.result(),
                () -> "the owner should have been able to disband their own party:\n"
                        + disbanded.text());
        assertTrue(server.callOnServerThread(
                        () -> Teams.of(server.server()).byId(commandPartyId).isEmpty()),
                "and the party is gone, so \"you are in no party\" is the true thing to tell them");

        for (UUID member : members) {
            server.callOnServerThread(() -> {
                TaskedNetworking.sendNoPartyTo(server.server(), member);
                return null;
            });
        }

        // One apiece rather than one per member: a disband arrives as one MEMBER_LEFT per member, each
        // naming its own player, so each of them is the whole audience of their own event. A count of
        // two for either would mean something walked the team instead.
        for (UUID member : members) {
            assertEquals(1, server.callOnServerThread(() -> TaskedNetworking.rostersSentTo(member)),
                    () -> member + " was not told their party is gone. Their panel is open, so this "
                            + "message is the only thing that can take it back to the empty state -- "
                            + "the disband fault was progress being sent without it");
        }

        note("a disband's members were each told they are in no party -- the roster message an open "
                + "panel redraws its empty state from");
    }

    @Test
    @Order(24)
    @DisplayName("a login is told about its party, and told when it has none")
    void aLoginIsToldAboutItsParty() {
        // **The regression test for a party a rejoin could not see.** The join push used to ask
        // `ProgressService.progressOwner` which team to send -- a second lookup, where every other push
        // in the mod passes a team id it already holds. A manager still reading its store answers that
        // lookup with a synthesised solo team keyed by the *player's* id, `PartySnapshot.of` finds no
        // such team, and `sendPartyToTeam` returns at its first line. The shape on screen was exact: a
        // party every command knew about, and a panel showing the empty state on every login.
        assertNotNull(commandPartyId, "order 19 should have created a party for this to recreate");

        HeadlessServer.Outcome created = asOperator("/tasked party create the-login-party");
        assertEquals(1, created.result(),
                () -> "a party to log in to should have been created:\n" + created.text());

        server.callOnServerThread(() -> {
            TaskedNetworking.forgetRosters();
            return null;
        });

        // The exact call the PLAYER_JOIN hook makes.
        server.callOnServerThread(() -> {
            TaskedNetworking.sendEverythingTo(player);
            return null;
        });

        assertEquals(1, server.callOnServerThread(() -> TaskedNetworking.rostersSentTo(player.getUUID())),
                "logging in with a party has to tell the client which one. Nothing else ever will --"
                        + " the panel is built from that message, and until the next member joins or"
                        + " leaves, the empty state is what it draws");

        HeadlessServer.Outcome left = asOperator("/tasked party leave");
        assertEquals(1, left.result(), () -> "and the party should be leavable:\n" + left.text());

        server.callOnServerThread(() -> {
            TaskedNetworking.forgetRosters();
            return null;
        });
        server.callOnServerThread(() -> {
            TaskedNetworking.sendEverythingTo(player);
            return null;
        });

        assertEquals(1, server.callOnServerThread(() -> TaskedNetworking.rostersSentTo(player.getUUID())),
                "and a login with no party is told that too. A client's cache holds whatever roster it"
                        + " was last sent, so silence here is a panel describing a party from the"
                        + " previous world -- which one client can see two of in a row");

        note("a login with a party was sent its roster, and a login without one was told so -- the two"
                + " halves of the join push, both silent while the panel was the only thing asking");
    }

    // ------------------------------------------------------------------
    // Driving and reading
    // ------------------------------------------------------------------

    /**
     * How many progress messages have been produced for one reason.
     *
     * <p>Read through the server thread, like every other read here, and not because the counter is
     * lock-protected -- it is a plain {@code HashMap}, written from the server thread and read here, so
     * reading it directly would be reading a {@code HashMap} from another thread while it may be
     * written. That happens to work today and is not a thing to build on.
     */
    private static int messagesSent(int reason) {
        return server.callOnServerThread(() -> QuestSync.messagesSent(reason));
    }

    /**
     * How many progress messages have been produced in total, whatever the reason.
     *
     * <p>This is the form that answers "did <i>anything</i> get sent", which is a different question
     * from "was the right message sent for this reason" and the only one that can be asked about a
     * refusal. Order 17's last assertion is exactly that: a command that changed nothing must be silent,
     * and silence means no message of <b>any</b> reason -- so asking per-reason would let a push under a
     * different reason through while the assertion still passed.
     *
     * <p>It exists rather than being spelled as a sum at the call site because a caller summing reasons
     * has to know which reasons exist, and that list grows. A total is defined by the counter, not by the
     * test.
     */
    private static int messagesSent() {
        return server.callOnServerThread(QuestSync::messagesSent);
    }

    /**
     * The same, for one recipient.
     *
     * <p>The recipient is the half that matters for the team-change push, and a total cannot supply it:
     * two messages that both reached the same player look identical to two that reached one player
     * each, and the second is the entire claim being made.
     */
    private static int messagesSentTo(int reason, ServerPlayer who) {
        return server.callOnServerThread(() -> QuestSync.messagesSentTo(reason, who.getUUID()));
    }

    /** Runs a command as an operator -- the same permission a player in ops.json has. */
    private static HeadlessServer.Outcome asOperator(String command) {
        HeadlessServer.Outcome outcome = server.run(player, 4, command);
        note(outcome.report());
        return outcome;
    }

    /**
     * The same, from a specific player's own source.
     *
     * <p>Needed because collecting a reward is per-player while progress is per-team: "the party can
     * collect this" is only a check if somebody other than the owner does the collecting, and it has to
     * be a real player's source rather than the owner's for that to mean anything.
     */
    private static HeadlessServer.Outcome asOperator(ServerPlayer who, String command) {
        HeadlessServer.Outcome outcome = server.run(who, 4, command);
        note(outcome.report());
        return outcome;
    }

    /**
     * Ticks the engine until {@code condition} holds, or {@code timeout} runs out.
     *
     * <p>The engine is time-based: a task's next evaluation is due a fixed number of ticks after the
     * last, so "did this complete" has to be asked repeatedly. Each call to {@code tick} goes through
     * the server thread, because that is where the loader's hook would call it from.
     *
     * <p>Not a fixed sleep, because a passing test should not take as long as a failing one.
     */
    private static boolean tickUntil(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            tickOnce();
            sleep(100);
        }
        return condition.getAsBoolean();
    }

    /**
     * One tick of the engine, recording which teams it reported as changed.
     *
     * <p>Recording it is the point. {@code tick} returns the owners whose progress moved, and that
     * return value is the <b>only</b> thing that tells a caller there is something to send — it is how
     * the missing progress sync was fixed. So a test that calls {@code tick} and throws the answer away
     * is testing the engine's arithmetic and not the thing that was broken.
     *
     * <p>Before this round, {@code tick} returned {@code void} and the only code anywhere that pushed
     * progress to a player was the handler for pressing Submit. Gathering eight oak logs completed the
     * quest, granted the reward and printed the completion message while the quest book went on showing
     * {@code 0 / 8} for the rest of the session — and no test could have noticed, because there was
     * nothing to assert about a method with no output.
     */
    private static void tickOnce() {
        java.util.Set<UUID> changed = server.callOnServerThread(() -> ProgressService.tick(server.server()));
        lastTick = java.util.Set.copyOf(changed);
        changedEver.addAll(changed);
    }

    /** A quest's state, computed the way the engine computes it. */
    private static QuestState stateOf(String questId) {
        return stateFor(player, questId);
    }

    /**
     * A quest's state <b>as one particular player sees it</b>.
     *
     * <p>The two-argument version exists because of what the party tests are about. With one player,
     * "whose progress" is not a question; with two in a party it is the entire question, and asking it
     * twice with different subjects is how "they share one record" becomes a check rather than a
     * claim. It also means a bug where one member's view is correct and the other's is not cannot hide
     * behind a single lookup.
     */
    private static QuestState stateFor(ServerPlayer who, String questId) {
        return server.callOnServerThread(() -> {
            UUID owner = ProgressService.progressOwner(server.server(), who);
            return ProgressService.resolutionFor(server.server(), owner).stateOf(quest(questId).quest());
        });
    }

    /** The progress owner a player's quest state is read from. Their team's id, solo or not. */
    private static UUID ownerOf(ServerPlayer who) {
        return server.callOnServerThread(() -> ProgressService.progressOwner(server.server(), who));
    }

    /**
     * Empties both players' inventories, on the server thread.
     *
     * <p>Needed because the playthrough runs as one long sequence in one world: by the time the party
     * tests start, the first player is carrying eight oak logs from the solo run -- punch_a_tree's
     * task does not consume them. A test that means "only the friend has the logs" has to make that
     * true rather than hope it.
     *
     * <p>Straight to the inventory rather than through {@code /clear}, and the reason is that a
     * second thing that can fail is a second thing to debug when this test does fail. The command
     * would be more "real", and it is not what is under test.
     */
    private static void clearInventories() {
        // A plain null check for the second player, rather than List.of(player, friend) which is what
        // this was first and which *throws* on a null element. It threw on the line above the note
        // that would have said the friend had been spawned, so three party tests failed on an
        // NPE from a helper and the transcript named the friend as null -- which reads like a
        // server problem and was a helper problem. Being an outside-the-test-framework class,
        // nothing here fails loudly at the call site; it just returns the wrong thing.
        //
        // Nullable-on-purpose: the first test that uses this spawns the second player, and the ones
        // before it never touch this. A helper that only works when called in the right order is fine
        // as long as calling it in the wrong order is not silently a no-op.
        server.onServerThread(() -> {
            for (ServerPlayer who : new ServerPlayer[] { player, friend }) {
                if (who == null) {
                    continue;
                }
                who.getInventory().clearContent();
                who.getInventory().setChanged();
            }
        });
    }

    /** How far along one task is recorded to be. Zero for a task nothing has ever written. */
    private static int recordedTask(String questId, int taskIndex) {
        return recordedTaskFor(player, questId, taskIndex);
    }

    /**
     * How the party this player is in currently counts.
     *
     * <p>Read through the server thread like every other read here, and through {@code PartyStore}
     * rather than through the command's output — so the assertion is about the state a restart would
     * read back, not about the wording of a message.
     */
    private static PartyMode modeOf(ServerPlayer who) {
        return server.callOnServerThread(() ->
                PartyStore.of(server.server()).modeOf(ProgressService.progressOwner(server.server(), who)));
    }

    /** The same, for whichever player's progress is the interesting one. */
    private static int recordedTaskFor(ServerPlayer who, String questId, int taskIndex) {
        return server.callOnServerThread(() -> {
            UUID owner = ProgressService.progressOwner(server.server(), who);
            return ProgressService.progressFor(server.server(), owner)
                    .progressOf(quest(questId).quest())
                    .progressOf(taskIndex);
        });
    }

    /** How many quests have any stored progress at all. The check that a refusal wrote nothing. */
    private static int storedQuestCount() {
        return server.callOnServerThread(() -> {
            UUID owner = ProgressService.progressOwner(server.server(), player);
            return ProgressService.progressFor(server.server(), owner).size();
        });
    }

    /** How many of {@code item} the player is carrying, whole inventory. */
    private static int countInInventory(Item item) {
        return countInInventoryOf(player, item);
    }

    /** The same, for whichever player's inventory is the interesting one. */
    private static int countInInventoryOf(ServerPlayer who, Item item) {
        return server.callOnServerThread(() -> {
            var inventory = who.getInventory();
            int found = 0;
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (!stack.isEmpty() && stack.getItem() == item) {
                    found += stack.getCount();
                }
            }
            return found;
        });
    }

    private static QuestIndex.QuestEntry quest(String id) {
        Optional<QuestIndex.QuestEntry> found = TaskedQuests.find(id);
        return found.orElseThrow(() -> new AssertionError(
                "no quest '" + id + "' is loaded, so this test is asking about nothing. "
                        + "Loaded: " + TaskedQuests.index().quests().size() + " quest(s)"));
    }

    private static void assertRefused(HeadlessServer.Outcome outcome, String why) {
        assertTrue(outcome.refused(), () -> outcome.command() + " should have been refused, because "
                + why + " -- but it returned " + outcome.result() + "\n" + outcome.text());
    }

    // ------------------------------------------------------------------
    // Small things
    // ------------------------------------------------------------------

    private static void note(String line) {
        TRANSCRIPT.add(line);
        System.out.println("    " + line);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for the engine", e);
        }
    }

    private static String render(Problems problems) {
        return problems.all().stream()
                .map(DataProblem::render)
                .reduce((a, b) -> a + "\n" + b)
                .orElse("(no problems at all)");
    }

    /**
     * Copies the worked examples into a config directory, and returns their names.
     *
     * <p>What {@code tasked/tools/seed_quests.py} does, in Java so the test needs nothing on PATH. The
     * two must agree, and the thing that keeps them agreeing is that neither holds a list: both walk
     * {@code tasked/tools/quests} and both skip anything whose name begins with {@code _}.
     *
     * <h2>Recursive, and it had to become recursive for the same reason the script did</h2>
     *
     * <p>It was a single {@code Files.list} over the quest directory, copying each {@code .json} whose
     * name did not begin with an underscore. That is exactly right for the flat format, where a file
     * <i>is</i> a whole tree, and it silently copies nothing at all under the folder format — where the
     * quests are four levels down inside {@code getting_started/first_steps/} and no file at the top
     * level is a quest.
     *
     * <p>The failure that produces is worth naming because of how it reads. The seeding step succeeds,
     * the load succeeds, and the quest book is empty — so the first place anyone looks is the loader,
     * which is working. Worse, this test's own {@code filesFound} assertion would have compared 0 with
     * the number of top-level files and passed, because both sides were counting the same nothing.
     *
     * <p>So it walks, and it applies the underscore rule to <b>every path segment</b> rather than to the
     * final name — the same rule {@code DeclaredPaths.isIgnored} states, and for the same reason: the
     * shipped {@code _schema} folder is a directory, and a rule that only tested the file's own name
     * would happily copy every one of its contents into a config directory.
     *
     * <p>Returns {@code /}-separated relative paths, not bare file names, because under the folder
     * format a name is not unique — {@code group.json} appears once per group and {@code chapter.json}
     * once per chapter.
     */
    private static List<String> seedExamples(Path configDir) throws IOException {
        Path target = configDir.resolve("tasked/quests");
        Files.createDirectories(target);

        List<String> copied = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(EXAMPLES)) {
            for (Path source : walk.filter(Files::isRegularFile).toList()) {
                Path relative = EXAMPLES.relativize(source);
                if (isIgnored(relative)) {
                    continue;
                }
                Path destination = target.resolve(relative.toString());
                Files.createDirectories(destination.getParent());
                Files.copy(source, destination);
                copied.add(relative.toString().replace('\\', '/'));
            }
        }
        Collections.sort(copied);

        if (copied.isEmpty()) {
            throw new IllegalStateException("no example quests at " + EXAMPLES.toAbsolutePath()
                    + " -- the tests run from the Gradle project directory, so this is relative to"
                    + " tasked/common, and the examples belong in tasked/tools/quests");
        }
        return copied;
    }

    /**
     * Whether a path under the examples directory is to be skipped.
     *
     * <p>Every segment, not just the last. See {@link #seedExamples} — the {@code _schema} folder is a
     * directory, and a rule that only looked at the file's own name would copy its contents.
     */
    private static boolean isIgnored(Path relative) {
        for (Path segment : relative) {
            if (segment.toString().startsWith("_")) {
                return true;
            }
        }
        return false;
    }

    /**
     * One seeded example's text, by the relative path this test seeded it under.
     *
     * <p>Was used by {@link #theSeededExamplesLoad} to decode each seeded file and count what was in
     * it. That count is impossible under the folder format — a {@code group.json} holds a list of names
     * and the names are the directory listing — so the count reads the paths themselves now, and this
     * has no callers. It is kept rather than deleted for the one thing it is still good for: a test that
     * wants to assert something about the <i>bytes</i> of an example, which nothing does today.
     *
     * <p>Concretely: "the seeded file on disk is the file the loader read" is a claim a per-byte
     * comparison would make and a count cannot. Nothing needs it yet, and inventing a use for it would
     * be worse than leaving it here with a note saying so.
     */
    private static String readExample(String relative) throws IOException {
        return Files.readString(EXAMPLES.resolve(relative), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    // ------------------------------------------------------------------
    // The two things Armature normally provides
    // ------------------------------------------------------------------

    /**
     * Just enough platform for {@code TaskedQuests.reload()}, which asks for the config directory.
     *
     * <p>{@code FABRIC} is a stand-in and not a claim, and the honest version of this note is why it
     * is not something else: {@link PlatformKind} has no constant for a test double, and adding one
     * would mean republishing Armature for the benefit of a test. Nothing in Tasked branches on
     * {@code kind()} — checked rather than assumed — so no code path here can take a Fabric-only route
     * by accident. If something ever does start branching on it, this is the line that becomes a lie.
     */
    private record PlaythroughPlatform(Path configDirectory) implements ArmaturePlatform {

        @Override
        public PlatformKind kind() {
            return PlatformKind.FABRIC;
        }

        @Override
        public boolean isModLoaded(String modId) {
            return "tasked".equals(modId) || "armature".equals(modId);
        }

        @Override
        public Optional<String> modVersion(String modId) {
            return isModLoaded(modId) ? Optional.of("test") : Optional.empty();
        }

        @Override
        public boolean isClient() {
            return false;
        }

        @Override
        public boolean isDevelopmentEnvironment() {
            return true;
        }

        @Override
        public Path gameDir() {
            return configDirectory.getParent();
        }

        @Override
        public Path configDir() {
            return configDirectory;
        }
    }

    /**
     * Refuses to register anything, loudly.
     *
     * <p>{@code Tasked.init()} is deliberately not called: it registers the quest book item and the
     * network payloads, and this playthrough needs neither — it drives the engine through commands.
     * Doing it that way keeps the test free of both loaders, which is the whole reason it can run
     * headless at all.
     *
     * <p>So if anything <i>does</i> try to register, that assumption has quietly stopped being true,
     * and this throws with the reason rather than failing later with a null registry.
     */
    private static final class NothingIsRegistered implements Registrar {

        @Override
        public Registrar forMod(String modId) {
            return this;
        }

        @Override
        public <T> void register(Registry<T> registry, ResourceLocation id, Supplier<? extends T> value) {
            throw new UnsupportedOperationException("the playthrough does not run Tasked.init(), so "
                    + "nothing here should be registering " + id + ". Something started depending on "
                    + "an item or a payload existing, and this test needs to know which.");
        }
    }
}
