# Tasked

A modern questing mod for **Fabric** and **NeoForge**, for **Minecraft 1.21.1**. Built as a
ground-up replacement for FTB Quests — not a port of it, and not a reimplementation.

> **Status: 0.1.0, and well past the skeleton.** The quest format and its validator, the
> progression engine, server-authoritative sync, the quest book and the in-game editor all work on
> both loaders. Tasked ships no quests of its own: it is an engine, and the worked questlines in
> `tools/quests/` are what an author reads to learn the format.

## What it does

**Quests are JSON, one file each.** A quests directory is a tree of groups and chapters: a
`group.json` at the root of each group, a `chapter.json` naming its quests in order, one quest per
file, and an optional `index.json` for tree-wide settings. Named reward tables sit alongside. The
loader validates before it loads — required and unknown fields, id shape, enum names, item and tag
existence, duplicate ids, dangling dependencies, cycles — and a file that fails is reported with
its path and line number and skipped rather than killing the server. The earlier flat-file format
is still read.

**Fifteen task types.** `item` (count, consume, fuzzy or strict matching, crafted-only),
`item_tag`, `checkmark`, `dimension`, `biome`, `structure`, `advancement`, `stat`, `location`,
`xp`, `fluid`, `observation` (look at a block, block state, block entity, entity type, or a tag of
them), `kill` (entity or tag, custom name, SNBT filter), `stage`, and `custom` for handlers a mod
registers.

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
completed quests, so editing a file cannot leave a save inconsistent. `/tasked reload` re-runs the
load without a restart.

**Parties share progress.** Built on Armature's team API. A party chooses how its members' counts
combine — `ONE_MEMBER` (the default), `POOLED`, or `OWNER_ONLY` — and progress, stages and claims
are server-authoritative. `/tasked party` covers create, invite, accept, leave, kick, disband and
mode.

**The quest book** opens from the quest book item or the **B** key: a pannable, zoomable canvas of
nodes with per-quest shape, size and icon scale, styled dependency lines, markdown quest
descriptions, live task progress and submit buttons, rewards with claim and claim-all, a
choice-reward picker, and the party roster.

**The editor is in-game and server-owned.** A player with permission level 2 gets Edit and Tools
panels inside the book: forms for every task and reward type, a searchable item picker, drag to
move a node with a snap grid, undo/redo, and Ctrl+S to save. The server re-checks permission and
validates before writing; the client never writes a file.

**For addons and packs.** `TaskedEvents` publishes quest, task, claim and stage events, and
`TaskTypes.register` / `RewardTypes.register` / `ConditionTypes.register` add custom types with
their own codecs and editor forms. On NeoForge, with KubeJS 7 present, Tasked also registers a
`Tasked` script binding and a `TaskedEvents` event group for pack scripting.

## Requirements

| | |
|---|---|
| Minecraft | 1.21.1 |
| Java | 21 |
| Fabric | Fabric Loader 0.16.9+, with Fabric API 0.109.0+1.21.1 |
| NeoForge | 21.1.252+ |
| Armature | 0.1.0+, required on both loaders |
| KubeJS | optional, NeoForge only, 2101.7.2+ |

Tasked is built on **Armature**, a standalone library and UI toolkit. Both metadata files declare
Armature as a required dependency, so install both mods — the loader names Armature if it is
missing.

## Building

Tasked compiles against Armature's published `armature-common` artifact rather than its source
tree, so Armature has to be in the local Maven repository first. From a sibling checkout:

```cmd
cd ..\armature
gradlew build :common:publishToMavenLocal

cd ..\tasked
gradlew build
```

Jars land in `fabric/build/libs` and `neoforge/build/libs`. Install the plain jar
(`tasked-fabric-1.21.1-0.1.0.jar`) — the `-sources` and `-javadoc` jars are not mods. The test
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
`tasked-...jar` and `tasked-... (1).jar` sitting side by side — Minecraft picks whichever it likes,
and the resulting bug hunt is never worth it.

Because Armature is a required dependency, its jars have to reach the profile too; run `deployAll`
in both repositories. If your profiles are named something else, edit these two lines in
`gradle.properties`:

```properties
testModsDirFabric=C:/Users/Ellio/AppData/Roaming/ModrinthApp/profiles/Tasked Fabric/mods
testModsDirNeoForge=C:/Users/Ellio/AppData/Roaming/ModrinthApp/profiles/Tasked NeoForge/mods
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
checks, and the KubeJS binding. `docs/tasked-quests.schema.json` plus the per-file schemas under
`tools/quests/_schema/` are the machine-readable field reference. `tools/README.md` describes the
worked questlines — eleven of them, each a different design rather than a different difficulty — and
how to seed them.

Based on the [MultiLoader Template](https://github.com/Jaredlll08/MultiLoader-Template), with the
Forge subproject removed.

## Licence

MIT — see [LICENSE](LICENSE).
