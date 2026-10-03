# Tasked documentation

Tasked is a questing engine for Minecraft 1.21.1, on Fabric and NeoForge. A pack author describes a
[[quest]] line in JSON, Tasked draws it on a pannable [[canvas]], and the server decides what counts
as done — the client renders what it is told and nothing more, so no player can complete a quest by
editing their own game.

> [!NOTE]
> **Tasked is at 0.1.0, and everything these pages describe works on both loaders.** The quest folder
> format and its [[validator]], the progression engine, server-authoritative sync, the quest book and
> its in-game editor are all built. 0.x is the promise that the format may still move; where a subject
> is unfinished, the page says so rather than describing what is planned.

## What it needs

| | |
|---|---|
| Minecraft | 1.21.1, on Fabric or NeoForge |
| Fabric | Fabric Loader 0.16.9+, with Fabric API 0.109.0+1.21.1 |
| NeoForge | 21.1.252+ |
| Armature | 0.1.0+, required on both loaders — [[armature:index]] is the library Tasked is built on |
| KubeJS | optional, NeoForge only, 2101.7.2+ — see [[tasked:authoring/kubejs]] |

Tasked ships no questlines of its own. It is an engine, and installing it does not put somebody else's
content in your config; the worked questlines live in the repository under `tools/quests/`, as
authoring material for a pack's first chapter.

## Where to start

| Page | What it is |
|---|---|
| [[tasked:authoring/quest-files]] | The folder format: chapter groups, chapters, one quest per file, and the order things load in |
| [[tasked:authoring/quests]] | Every field on a quest, from its place on the canvas to the flags that hide it |
| [[tasked:authoring/tasks]] | The fifteen task types, and what each one counts |
| [[tasked:authoring/rewards]] | The ten reward types, and the reward tables behind four of them |
| [[tasked:authoring/validation]] | What the validator checks, and what a mistake reads like |
| [[tasked:commands]] | The `/tasked` commands, for players and operators |
| [[tasked:authoring/kubejs]] | Driving a questline from a KubeJS script |

## From files to a running questline

<Steps>
  <Step title="Write the quest files">
    A pack is folders and JSON under `config/tasked/quests/`. The shape of that tree is the subject of
    [[tasked:authoring/quest-files]]; the short version is one folder per [[chapter]], one file per quest.
  </Step>
  <Step title="Load it">
    ```cmd
    /tasked reload
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

A player with permission level 2 gets **Edit** and **Tools** in the book's header. That is the in-game
editor, and it writes the same files these pages describe — the server re-checks the permission and
validates before anything lands on disk, so the client never writes a file.
