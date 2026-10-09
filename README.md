# Tenet

A modern questing mod for **Fabric** and **NeoForge**, for **Minecraft 1.21.1**. Built as a
ground-up replacement for FTB Quests — not a port of it, and not a reimplementation.

> **Status: 0.1.5 — pre-1.0, and not yet a stability promise.** The quest format and its validator,
> the progression engine, server-authoritative sync, the quest book and the in-game editor all work
> on both loaders, but the version is below 1.0 on purpose: a break in the quest format needs only a
> minor bump, so read the release notes before upgrading. Tenet ships no quests of its own: it is an
> engine, and the worked questline in `tools/quests/` is what an author reads to learn the format.

## What it does

**Quests are JSON, one file each.** A quests directory is a tree of groups and chapters: a
`group.json` at the root of each group, a `chapter.json` naming its quests in order, one quest per
file, and an optional `index.json` for tree-wide settings. Named reward tables sit alongside. The
loader validates before it loads — required and unknown fields, id shape, enum names, item existence,
duplicate ids, dangling dependencies, cycles — and a file that fails is reported with
its path and line number and skipped rather than killing the server. The earlier flat-file format
is still read.

**Fifteen task types.** `item` (count, consume, fuzzy or strict matching, crafted-only, component
filter), `item_tag`, `checkmark`, `dimension`, `biome`, `structure`, `advancement`, `stat`,
`location`, `xp`, `fluid`, `observation` (look at a block, a block tag, a block state, a block
entity, a block entity type, an entity type, or an entity type tag), `kill` (entity or tag, custom
name, SNBT filter), `stage`, and `custom` for handlers a mod registers.

**Ten reward types.** `item`, `xp`, a weighted `random` table, a `loot` table that can come up
empty, `all_table`, `choice` (the player picks), `command`, `advancement`, `stage`, and `custom`.

**Six condition types.** `item`, `item_tag`, `score` (a scoreboard objective), `advancement`,
`stage`, and `party_size` — gates a task or a reward can carry as a `conditions` list. A measured
task counts only for members who meet them, a submit is refused without them, and a reward is paid
only to a player who does; a gated row is drawn locked in the book, with the unmet conditions named
on hover.

**Progression is declarative.** Prerequisite modes (`ALL_COMPLETED`, `ONE_COMPLETED`,
`ALL_STARTED`, `ONE_STARTED`) with an optional `minRequired` count for OR-gates; chapters are
`LINEAR` or `FLEXIBLE`; quests can repeat on a cooldown, run their tasks sequentially or
all-optional, stay hidden until revealed, gate on a per-player stage, and branch exclusively —
either by a named group or by a cap on completable dependants. Progress is recomputed from
completed quests, so editing a file cannot leave a save inconsistent. `/tenet reload` re-runs the
load without a restart.

**Parties share progress.** Built on Armature's team API. A party chooses how its members' counts
combine — `ONE_MEMBER` (the default), `POOLED`, or `OWNER_ONLY` — and progress, stages and claims
are server-authoritative. `/tenet party` covers create, invite, accept and decline, joining an open
party, rename, transfer and hand-over, uninvite, the two membership settings (`open` and
`member-invites`), leave, kick, disband and mode. Bare `/tenet party` also reports which source the
parties are being read from, which is how an operator tells Tenet's own teams from another mod's.

**The quest book** opens from the quest book item or the **B** key: a pannable, zoomable canvas of
nodes with per-quest shape, size and icon scale, styled dependency lines, markdown quest
descriptions, live task progress and submit buttons, a claim menu grouped by chapter with claim,
claim-chapter and claim-all, a choice-reward picker, and the party roster.

**The editor is in-game and server-owned.** A player with permission level 2 gets a band under the
book's title row carrying `Author`, `Assets`, `✎ Edit`, `Advanced` and `Preview`: `Edit` latches edit mode, `Author`
opens the tools panel inside the book, and `Assets` opens the pack's own files — forms for every task and
reward type, a searchable item picker, drag to move a node with a snap grid, and undo/redo. `Advanced`
sets how much of the editor is drawn: with it off — the default — every menu carries the essentials, and
with it on the reward tables, the visibility and progression rules, a chapter's line styles and the theme
editor are there as well. `Preview` shows the player's view: the book shrinks to the reader's card at the
reader's size, hidden chapters and quests are hidden again, and editing is paused — while edit mode stays
on underneath, so leaving the preview resumes exactly where it was left; the way back out is the lit pill
floating over the canvas, since the reader's card has no band. Nodes are selectable in bulk — shift-click collects, ctrl-click
toggles one in or out, shift-drag on the empty canvas is a marquee, and Ctrl+A takes the chapter — and
a drag, Ctrl+C/Ctrl+V, Ctrl+D or Delete then acts on the whole selection. A bulk edit goes out as one
batch per chapter it spans, applied all-or-nothing, so it is one history step and one Ctrl+Z per
chapter rather than one per quest. Every edit is written as it is made, so Ctrl+S reports that rather
than saving. The server re-checks permission and validates before writing: no quest file is written by
the client, and the player's own look and text size are the client's own files.

**Nothing is deleted, and nothing one-shots.** Every control that takes something away asks twice — the
Delete key included, which arms on the first press and says what it is about to remove — and the delete
itself is a rename: a quest file, a chapter's whole folder or a table becomes `<name>.deleted`, and the
loader skips that suffix everywhere. `Ctrl+Z` puts it back, and the copy outlives the history that the
key uses, so `/tenet removed` lists what is set aside and `/tenet restore <path>` takes one back.

**For addons and packs.** `TenetEvents` publishes quest, task, claim and stage events, and
`TaskTypes.register` / `RewardTypes.register` / `ConditionTypes.register` add custom types with
their own codecs and editor forms. On NeoForge, with KubeJS 7 present, Tenet also registers a
`Tenet` script binding and a `TenetEvents` event group for pack scripting.

## Requirements

| | |
|---|---|
| Minecraft | 1.21.1 |
| Java | 21 |
| Fabric | Fabric Loader 0.16.9+, with Fabric API 0.109.0+1.21.1 |
| NeoForge | 21.1.252+ |
| Armature | 0.1.1+, required on both loaders |
| KubeJS | optional, NeoForge only, 2101.7.2+ |

Tenet is built on **Armature**, a standalone library and UI toolkit. Both metadata files declare
Armature as a required dependency, so install both mods — the loader names Armature if it is
missing.

## Building

Tenet compiles against Armature's published `armature-common` artifact, which Armature's release
workflow publishes to its own Maven repository on every tag. So this is the whole of it:

```cmd
gradlew build
```

The version it compiles against is `armature_version` in `gradle.properties`, and that version has to
be released before a build here can use it — the release workflow checks, and says so by name.

To build against an *unreleased* Armature instead — a change on its `main`, before any tag — publish
it locally first. `mavenLocal` is declared ahead of the remote repository, so the local copy wins:

```cmd
cd ..\armature
gradlew build publishToMavenLocal

cd ..\tenet
gradlew build
```

### Writing a handler mod

Tenet's own API is on the same repository, because a separate mod can register its own
`tenet:custom` task and reward types — the arrangement [docs/authoring/tasks.md](docs/authoring/tasks.md)
describes as *"a pack shipping a handler mod as an optional dependency"*:

```groovy
repositories {
    maven { url = 'https://maven.ellipog.dev' }
}

dependencies {
    // TenetScripts, TenetEvents and the CustomTask / CustomReward registries.
    compileOnly 'dev.ellipog:tenet-common-1.21.1:0.1.5'
    // TenetEvents extends Armature's Event, so that one is needed beside it.
    compileOnly 'dev.ellipog:armature-common-1.21.1:0.1.5'
}
```

`compileOnly`, because both are separate mod jars at runtime — the loader supplies them from the
`mods` folder. The `-fabric` and `-neoforge` siblings are on the same repository for a handler mod's
`runtimeOnly` in a dev run.

A KubeJS pack needs none of this. Scripts register the same handlers through the plugin KubeJS finds
inside Tenet's jar, which is [docs/authoring/kubejs.md](docs/authoring/kubejs.md).

Jars land in `fabric/build/libs` and `neoforge/build/libs`. Install the plain jar
(`tenet-fabric-1.21.1-0.1.5.jar`) — the `-sources` jars are not mods. The test
suite is JUnit 5 and runs headless, as part of `gradlew build`.

## Deploying to a local test profile

Three tasks copy the freshly built jars straight into the Modrinth App profiles named in
`gradle.properties`:

```cmd
gradlew deployFabric     :: Fabric profile only
gradlew deployNeoForge   :: NeoForge profile only
gradlew deployAll        :: both
```

Each one deletes the previous copies from the profile before copying, so you never end up with
`tenet-...jar` and `tenet-... (1).jar` sitting side by side — Minecraft picks whichever it likes,
and the resulting bug hunt is never worth it.

Because Armature is a required dependency, its jars have to reach the profile too; run `deployAll`
in both repositories. If your profiles are named something else, edit these two lines in
`gradle.properties`:

```properties
testModsDirFabric=C:/Users/Ellio/AppData/Roaming/ModrinthApp/profiles/Mod Testing Fabric 1.21.1/mods
testModsDirNeoForge=C:/Users/Ellio/AppData/Roaming/ModrinthApp/profiles/Mod Testing NeoForge 1.21.1/mods
```

## Layout

| Path | What goes there |
|---|---|
| `common/` | Compiled against vanilla only — the bulk of the code. Cannot see either loader. |
| `fabric/` | Fabric entry points and anything Fabric-specific. |
| `neoforge/` | NeoForge entry points, the KubeJS plugin, and anything NeoForge-specific. |
| `docs/` | The manual: `index.md`, the command reference, the authoring guide under `authoring/`, and the published JSON schema. |
| `tools/` | Not compiled and not shipped: the worked questlines, their per-file schemas, and the script that seeds them into a config directory. |

`common/` cannot reference `fabric/` or `neoforge/`. That direction is enforced by the build, not
by convention.

## Documentation

`docs/index.md` is the front door, and the pages under `docs/authoring/` are the manual: the quest
folder format, every field on a quest, the fifteen task and ten reward types, what the validator
checks, and the KubeJS binding. The five per-kind schemas under `tools/quests/_schema/` — group,
chapter, quest, index and reward table — are the machine-readable field reference, published at
`https://ellipog.dev/tenet/_schema/`; `docs/tenet-quests.schema.json` is the older one-file
format's, published under `_legacy/`. `tools/README.md` describes the worked questline — one
chapter, `first_light/first_steps`, laid out as an orrery and carrying every field the format has —
and the two scripts that regenerate and seed it.

Based on the [MultiLoader Template](https://github.com/Jaredlll08/MultiLoader-Template), with the
Forge subproject removed.

## Licence

MIT — see [LICENSE](LICENSE).
