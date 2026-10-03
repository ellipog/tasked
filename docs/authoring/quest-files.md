# The quest folder

Everything Tasked loads lives under one root, `config/tasked/quests/`. The folder layout *is* the
format: a [[chapter-group]] is a folder with a manifest, a [[chapter]] is a folder inside it, and each
[[quest]] is one file.

```
config/tasked/quests/
├── index.json                  the root: what exists, and in what order
├── reward_tables/              rolls a reward can name
│   └── dungeon.json
├── getting_started/            a chapter group
│   ├── group.json
│   └── first_steps/            a chapter
│       ├── chapter.json
│       └── punch_a_tree.json   one quest
└── stuck_in_the_past.json      an older single-file questline — still read
```

## Groups and chapters

A group folder holds a `group.json`. Its `chapters` array names the group's chapter folders, in the
order they should appear — that list is the only place the order is expressed:

```json
{
  "id": "getting_started",
  "title": "Getting Started",
  "chapters": ["first_steps"]
}
```

A chapter folder holds a `chapter.json`. Its `quests` array names the chapter's quest files, in order,
and that order is **load-bearing rather than cosmetic**: in a `linear` chapter it *is* the progression,
because each quest unlocks when the one above it completes.

```json
{
  "id": "first_steps",
  "title": "First Steps",
  "subtitle": "Five quests, no tricks",
  "progressionMode": "linear",
  "quests": [
    "punch_a_tree.json",
    "make_a_table.json",
    "stone_tools.json"
  ]
}
```

The fields a group or a chapter can carry beyond those are on [[tasked:authoring/quests]], because they are the
same fields a quest inherits from them: `defaultPrerequisiteMode`, `defaultConsumeItems`, and the
`dependencyStyle` a chapter's lines are drawn with.

> [!WARNING]
> **A folder's name is its id, and that is checked.** A folder called `getting_started` whose manifest
> says `"id": "first_steps"` is reported, naming both sides — and the folder name wins, because every
> path in the tree is built from it. The same goes for the name lists: a chapter named in `chapters`
> that is not on disk is reported, and a chapter folder that no manifest names is reported too.

## The root `index.json`

The root's `index.json` does two jobs: it lists the top level of the book in reading order, and it
holds the tree's own settings.

```json
{
  "settings": {
    "defaultAutoClaim": "disabled",
    "defaultTeamReward": false,
    "suppressAllAutoclaiming": false,
    "detectionDelay": 20
  },
  "entries": [
    { "group": "getting_started" },
    { "chapter": "a_lone_chapter" },
    { "file": "stuck_in_the_past.json" }
  ]
}
```

Each entry names exactly one of a `group` folder, a bare `chapter` that belongs to no group, or a
version-1 `file` at the root. The settings are the tree's defaults, each overridable closer to the
thing it affects:

| Setting | Default | What it does |
|---|---|---|
| `defaultAutoClaim` | `disabled` | What a reward with `auto: "default"` does — see [[tasked:authoring/rewards]] |
| `defaultTeamReward` | `false` | Whether a reward that does not say otherwise is one claim for the team |
| `suppressAllAutoclaiming` | `false` | Holds every automatic payout, whatever individual rewards say — an operator's switch for an event |
| `detectionDelay` | `20` | Ticks after a player joins before their first task check, so a login does not run the whole book on one tick |

An absent `index.json` is not an error: the tree is ordered the old way, folders by name, and that is
how every pack written before the file existed still loads.

> [!NOTE]
> Group folders come before their contents in reading order, but nothing above them declares an order
> unless `index.json` does — which is why **renaming a group folder can reorder the book** when the
> file is absent. With an `index.json`, the order is authored and a rename changes nothing but the id.

## Ids, aliases, and the cost of a rename

An id is lowercase letters, digits and underscores, at most 64 characters. It appears in player
progress files, which is the whole reason `aliases` exists: a renamed quest, chapter or group lists
its former ids, and nothing that referenced the old name breaks.

> [!CAUTION]
> **Renaming an id without an alias orphans the progress stored under the old one.** A player keeps
> the completion; the quest no longer recognises it, so it reads as incomplete and their rewards for
> it are gone. Add the old id to `aliases` in the same edit, and there is nothing to fix.

## Reward tables

Rolls live in `reward_tables/`, one file per table, named without the `.json` suffix. A reward names
one with `"table": "dungeon"`; the file's own format is on [[tasked:authoring/rewards]].

## Underscores are skipped, everywhere

Any file or folder whose name begins with `_` is skipped by the loader — the convention that lets a
schema folder, a note, or a draft sit beside the content without being loaded. It is a
[[declared-path]]: the mod reads its directories by name rather than scanning them, so a stray file
cannot be mistaken for content. `tools/quests/_schema/` in the repository is the worked example.

## The older, single-file format

<details>
<summary>If you have a questline written as one flat document</summary>

Before the folder format, a questline was one `.json` document holding the whole tree as
`chapterGroups[]`. Those files are still read, forever: a `.json` file at the root of the quest folder
is recognised by *position*, not by a `version` field, so an old file needs no edit to keep working.

The two formats describe the same objects, so a flat file may carry the same fields a `group.json`
does. New packs should use the folder format; the flat one is kept so old ones do not have to be
rewritten.

</details>

## Reloading

`/tasked reload` re-reads the folder without a restart, and re-syncs every connected player's tree and
progress — a quest removed, an id renamed, or a dependency broken is visible immediately rather than
on the next reconnect. A file with an error is reported and skipped; the rest of the pack still loads.
[[tasked:authoring/validation]] is the page about what those reports say.
