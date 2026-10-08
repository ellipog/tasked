# Tenet documentation

Tenet is a questing engine for Minecraft 1.21.1, on Fabric and NeoForge. A pack author describes a
[[quest]] line in JSON, Tenet draws it on a pannable [[canvas]], and the server decides what counts
as done — the client renders what it is told and nothing more, so no player can complete a quest by
editing their own game.

> [!NOTE]
> **Tenet is at 0.1.4 — pre-1.0 — and everything these pages describe works on both loaders, except
> where a page says otherwise** (the KubeJS binding is NeoForge-only, and its page says so). The
> quest folder format and its [[validator]], the progression engine, server-authoritative sync, the
> quest book and its in-game editor are all built, but the version is below 1.0 on purpose: a break
> in the format needs only a minor bump. Where a subject is unfinished, the page says so rather than
> describing what is planned.

## What it needs

| | |
|---|---|
| Minecraft | 1.21.1, on Fabric or NeoForge |
| Fabric | Fabric Loader 0.16.9+, with Fabric API 0.109.0+1.21.1 |
| NeoForge | 21.1.252+ |
| Armature | 0.1.1+, required on both loaders — [[armature:index]] is the library Tenet is built on |
| KubeJS | optional, NeoForge only, 2101.7.2+ — see [[tenet:authoring/kubejs]] |

Tenet ships no questlines of its own. It is an engine, and installing it does not put somebody else's
content in your config; the worked questline lives in the repository under `tools/quests/`, as
authoring material for a pack's first chapter.

## Where to start

| Page | What it is |
|---|---|
| [[tenet:authoring/quest-files]] | The folder format: chapter groups, chapters, one quest per file, and the order things load in |
| [[tenet:authoring/quests]] | Every field on a quest, from its place on the canvas to the flags that hide it |
| [[tenet:authoring/tasks]] | The fifteen task types, and what each one counts |
| [[tenet:authoring/rewards]] | The ten reward types, and the reward tables behind four of them |
| [[tenet:authoring/conditions]] | The six condition types — gating a task or a reward on an item, a tag, a score, an advancement, a stage or a party's size |
| [[tenet:authoring/languages]] | Shipping a questline in more than one language: `lang/<locale>.json`, which file a player gets, and the keys |
| [[tenet:authoring/validation]] | What the validator checks, and what a mistake reads like |
| [[tenet:commands]] | The `/tenet` commands, for players and operators |
| [[tenet:authoring/kubejs]] | Driving a questline from a KubeJS script |

## From files to a running questline

<Steps>
  <Step title="Write the quest files">
    A pack is folders and JSON under `config/tenet/quests/`. The shape of that tree is the subject of
    [[tenet:authoring/quest-files]]; the short version is one folder per [[chapter]], one file per quest.
  </Step>
  <Step title="Load it">
    ```cmd
    /tenet reload
    ```

    Reads the folder without a restart and reports what loaded, what it refused, and why.
  </Step>
  <Step title="Look at it">
    Press `B`, or use the Quest Book item. The book draws exactly what the server loaded, and a task's
    progress ticks as you play.
  </Step>
</Steps>

## The book, briefly

A player opens the quest book from the **Quest Book** item or the `B` key. It is a pannable, zoomable
canvas of nodes: a quest's `x`/`y` is its place, its shape, size, rotation and icon are all authored,
and the lines between nodes are its dependencies. From a quest's card a player submits tasks, claims
[[reward]]s, and picks between a choice reward's entries. A player in a party sees the roster there
too, and the counts the party's mode combines.

An operator gets three pills over the canvas's top-left corner — `Panels`, `Assets` and `✎ Edit` — and
the header itself carries no authoring control. `Panels` latches the author's dock, which is where the
Book and Chapter tabs live; `Assets` opens the pack's own files; `Edit` latches edit mode and nothing
else. That is the in-game editor, and it writes the same files these pages describe: the server
re-checks the permission and validates before anything lands on disk, so no quest file is written by the
client — the player's own look and text size live in files the client owns.

## Removing something, and getting it back

**Every control that takes something away asks twice.** The quest card's `Delete`, the node and
dependency menus, the chapter and group menus, an entry's `×`, the table browser's `×`, the Assets
panel's `×` and the `Delete` key all arm on the first press and act on the second, and the ones that
remove a whole chapter or group say what goes with it — `Really delete? (70 quests, 3 dependent)` — before
they do. Nothing is erased when they act: a quest file is renamed to `<name>.json.deleted`, a chapter's
or a group's whole folder becomes `<name>.deleted`, and a reward table becomes `<name>.json.deleted`. The
loader skips that suffix everywhere, so a pack with removed content in it still loads.

**Ctrl+Z takes it back**, and the restore is one history step per chapter — the same key as any other
edit. The history is the *server's*, though, so it does not survive a restart, and `/tenet reload` drops
it on purpose; after sixty further edits in that chapter the step has fallen off the end. The copy on
disk survives all three, which is what makes the pair below the way back:

| Command | What it does |
|---|---|
| `/tenet removed` | Everything set aside under the quest folder, with the name each would come back as. |
| `/tenet restore <path>` | Puts one back, exactly as it was, and re-lists it where it belongs. |

Neither is offered in the book, because a tombstone is skipped by every walk the editor draws from: the
tree genuinely does not know it is there, and a panel that listed things the tree cannot see would be a
second opinion about the pack. Both take permission level 2, like `reload`.
