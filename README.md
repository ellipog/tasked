# Tenet

The modern questing engine for Fabric and NeoForge.

Tenet is a ground-up replacement for FTB Quests — not a port, not a reimplementation.
A pack author describes a quest line in JSON, Tenet draws it in a quest book, and the
server decides what counts as done. The client renders what it is told and nothing more,
so no player can complete a quest by editing their own game.

Tenet ships no quests of its own. It is an engine. The worked example under
`tools/quests/` is what an author reads to learn the format.

> **Before its first stable release.** Everything below works on both loaders, but the
> quest format and the API are still allowed to break. Read the release notes before
> upgrading.

## How it works

**Quests are JSON, one file each.** A quest directory is a tree of groups and chapters:
a `group.json` at the root of each group, a `chapter.json` naming its quests in order,
one quest per file, and an optional `index.json` for tree-wide settings. Named reward
tables sit alongside.

The loader validates before it loads — required and unknown fields, id shape, enum
names, item existence, duplicate ids, dangling dependencies, cycles — and a file that
fails is reported with its path and line number, then skipped instead of taking the
server down with it. An older flat-file layout is still read. `/tenet reload` re-runs
the load without a restart, and editing a file can never leave a save inconsistent
because progress is recomputed from completed quests.

**Tasks are what a quest asks for.** Fifteen types, each with its own fields:

`item` (count, consume, fuzzy or strict matching, crafted-only, component filter),
`item_tag`, `checkmark`, `dimension`, `biome`, `structure`, `advancement`, `stat`,
`location`, `xp`, `fluid`, `observation` (look at a block, block tag, block state,
block entity, block entity type, entity type, or entity type tag), `kill` (entity or
tag, custom name, SNBT filter), `stage`, and `custom` for handlers a mod registers.

A task that takes nothing completes itself when its count is met. A task that takes
something waits for the player to press Submit — the press is the consent to take it.
`/tenet types` lists everything the build in front of you has, with the fields each
type takes.

**Rewards are what a quest gives.** Ten types: `item`, `xp`, a weighted `random` table,
a `loot` table that may pay nothing, `all_table`, `choice` (the player picks), `command`,
`advancement`, `stage`, and `custom`. Four of them draw from named reward tables, which
can be rolled, imported from a chest, and exported back into one from `/tenet table`.

By default rewards wait to be claimed — one reward, one chapter, or the whole book from
the claim menu — and a chapter can switch the whole ladder to auto-claim with a single
flag.

**Conditions gate tasks and rewards.** Six types — `item`, `item_tag`, `score`
(a scoreboard objective), `advancement`, `stage`, and `party_size` — carried as a
`conditions` list. A measured task counts only for members who meet them, a submit is
refused without them, and a reward is paid only to a player who does. A gated row draws
locked in the book, with the unmet conditions named on hover.

**Progression is declarative.** Prerequisite modes (`ALL_COMPLETED`, `ONE_COMPLETED`,
`ALL_STARTED`, `ONE_STARTED`) with an optional `minRequired` count for OR-gates.
Chapters are `LINEAR` or `FLEXIBLE`. Quests can repeat on a cooldown, run their tasks
sequentially or all-optional, stay hidden until revealed, gate on a per-player stage,
and branch exclusively — by named group or by a cap on completable dependants.

**Parties share progress.** Built on Armature's team API. A party chooses how its
members' counts combine — `ONE_MEMBER`, `POOLED`, or `OWNER_ONLY` — and progress,
stages, and claims are server-authoritative. `/tenet party` covers create, invite,
accept and decline, joining an open party, rename, transfer, uninvite, the two
membership switches (`open` and `member-invites`), leave, kick, disband, and mode.
Bare `/tenet party` also reports which mod the parties are being read from, which is
how an operator tells Tenet's own teams apart from another mod's.

## The quest book

Opens from the Quest Book item or the `B` key: a pannable, zoomable canvas of nodes
with per-quest shape, size, and icon scale, styled dependency lines, Markdown quest
descriptions, live task progress with submit buttons, a claim menu grouped by chapter,
a choice-reward picker, and the party roster. Quests can also be pinned to the HUD,
with notices for task and quest completion.

## The in-game editor

A player with operator permission gets a band under the book's title row carrying
`Author`, `Assets`, `Edit`, `Advanced`, and `Preview`:

- `Edit` latches edit mode. `Author` opens the tools panel inside the book.
  `Assets` opens the pack's own files.
- Forms for every task and reward type, a searchable item picker, drag to move a node
  with a snap grid, and undo/redo.
- `Advanced` sets how much of the editor is drawn. Off — the default — every menu
  carries the essentials. On, the reward tables, visibility and progression rules,
  chapter line styles, conditions, and the theme editor appear as well.
- `Preview` shows the player's view: the book shrinks to the reader's card, hidden
  chapters and quests hide again, and editing pauses — while edit mode stays on
  underneath, so leaving the preview resumes exactly where it was left.

Nodes select in bulk — shift-click collects, ctrl-click toggles one in or out,
shift-drag on empty canvas is a marquee, ctrl-A takes the chapter — and a drag, copy,
paste, duplicate, or delete then acts on the whole selection. A bulk edit goes out as
one batch per chapter it spans, applied all-or-nothing: one history step and one
undo per chapter, not one per quest. Every edit is written as it is made, so there is
no save button to forget.

The server re-checks permission and validates before writing. No quest file is ever
written by the client, and the player's own look and text size live in files the
client owns.

**Nothing is deleted, and nothing one-shots.** Every control that takes something away
asks twice — the Delete key included, which arms on the first press and names what it
is about to remove. The delete itself is a rename: a quest file, a chapter's whole
folder, or a table becomes `<name>.deleted`, and the loader skips that suffix
everywhere. Ctrl-Z puts it back, and the copy on disk outlives the history, so
`/tenet removed` lists what is set aside and `/tenet restore <path>` brings one back.

## Installing

Install **Tenet and Armature** together, on client and server. Both metadata files
declare Armature as a required dependency, so the loader names it if it is missing.

See the Modrinth page for the supported Minecraft version, the required loader and
library versions, and which Armature release to pair with this one. Tenet installs no
content — a server with no quest files loads an empty tree, which is the correct
starting point, not an error.

## Writing quests

1. Put folders and JSON under `config/tenet/quests/` — one folder per chapter group,
   one chapter folder per chapter, one file per quest.
2. Run `/tenet reload`. It reports what loaded, what it refused, and why.
3. Press `B`. The book draws exactly what the server loaded.

Start from the worked questline in `tools/quests/`: one chapter laid out as an orrery
that carries every field the format has. `tools/README.md` describes it and the two
scripts that regenerate and seed it into a config directory.

| Guide | What it covers |
|---|---|
| `docs/authoring/quest-files.md` | The folder format and load order |
| `docs/authoring/quests.md` | Every field on a quest |
| `docs/authoring/tasks.md` | Every task type and its fields |
| `docs/authoring/rewards.md` | Every reward type and the tables behind them |
| `docs/authoring/conditions.md` | Every condition type |
| `docs/authoring/validation.md` | What the validator checks, and what a mistake reads like |
| `docs/authoring/languages.md` | Shipping a quest line in more than one language |
| `docs/authoring/kubejs.md` | Driving quests from a KubeJS script (NeoForge only) |
| `docs/authoring/ftb-mapping.md` | Which FTB Quests fields already have a Tenet home |
| `docs/commands.md` | Every `/tenet` command |
| `docs/hud.md` | Pinning quests and placing notices |

The per-kind schemas under `tools/quests/_schema/` are the machine-readable field
reference. `docs/tenet-quests.schema.json` covers the older single-file layout.

## For addon mods and packs

`TenetEvents` publishes quest, task, claim, and stage events. `TaskTypes.register`,
`RewardTypes.register`, and `ConditionTypes.register` add custom types with their own
codecs and editor forms. On NeoForge, with KubeJS present, Tenet also registers a
`Tenet` script binding and a `TenetEvents` event group for pack scripting.

A handler mod compiles against Tenet's published API — no clone, nothing to publish
first. See the releases page for the current coordinate, in this shape:

```groovy
repositories {
    maven { url = 'https://maven.ellipog.dev' }
}

dependencies {
    // TenetScripts, TenetEvents, and the custom task / reward registries.
    // compileOnly: both mods are separate jars at runtime.
    compileOnly 'dev.ellipog:tenet-common-<minecraft-line>:<release>'
    // TenetEvents extends Armature's Event, so that one is needed beside it.
    compileOnly 'dev.ellipog:armature-common-<minecraft-line>:<release>'
}
```

A KubeJS pack needs none of this — scripts register the same handlers through the
plugin KubeJS finds inside Tenet's jar.

## Building

```cmd
gradlew build
```

The test suite runs headless as part of the build. Jars land in `fabric/build/libs`
and `neoforge/build/libs` — install the plain jars, not the `-sources` jars.

Tenet compiles against Armature's published artifact, resolved from Armature's Maven
repository on every release. The pin it compiles against is `armature_version` in
`gradle.properties`, and that release has to exist before a build here can use it.

To build against an unreleased Armature instead — a change on its main branch before
any release — publish it locally first. `mavenLocal` is declared ahead of the remote
repository, so the local copy wins:

```cmd
cd ..\armature
gradlew build publishToMavenLocal

cd ..\tenet
gradlew build
```

Three tasks copy freshly built jars into the Modrinth App profiles named in
`gradle.properties`:

```cmd
gradlew deployFabric     :: Fabric profile only
gradlew deployNeoForge   :: NeoForge profile only
gradlew deployAll        :: both
```

Each deletes the previous copies first, so stale duplicates never sit side by side.
Because Armature is required, run the deploy in both repositories. If your profiles
are named differently, edit the two `testModsDir` lines in `gradle.properties`.

## Layout

| Path | What goes there |
|---|---|
| `common/` | The bulk of the code, compiled against vanilla only. Cannot see either loader. |
| `fabric/` | Fabric entry points and anything Fabric-specific. |
| `neoforge/` | NeoForge entry points, the KubeJS plugin, and anything NeoForge-specific. |
| `docs/` | The manual: `index.md`, the command reference, the authoring guide under `authoring/`, and the published schema. |
| `tools/` | Not compiled and not shipped: the worked quest lines, their per-file schemas, and the scripts that seed them. |

`common/` cannot reference `fabric/` or `neoforge/`. That direction is enforced by
the build, not by convention.

Based on the [MultiLoader Template](https://github.com/Jaredlll08/MultiLoader-Template),
with the Forge subproject removed.

## Links

- Manual: `docs/index.md`, mirrored at [ellipog.dev](https://ellipog.dev/docs/tenet/)
- Community: see the Discord link in `gradle.properties`
- Requires: [Armature](https://github.com/ellipog/armature)

## Licence

MIT — see [LICENSE](LICENSE).
