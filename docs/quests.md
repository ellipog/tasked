# Quests

A [[quest]] is one file, and this page is the fields it can carry. Three groups: how it introduces
itself, where it sits on the [[canvas]], and what it does — dependencies, progression, visibility and
gates. The [[task]]s and [[reward]]s it holds have pages of their own.

```json
{
  "id": "punch_a_tree",
  "title": "Punch a Tree",
  "subtitle": "Eight logs, and it counts them itself",
  "description": ["Everything worth having starts with a tree."],
  "icon": { "item": "minecraft:oak_log" },
  "x": 0,
  "y": 0,
  "tasks": [
    { "type": "tasked:item", "item": "minecraft:oak_log", "count": 8 }
  ],
  "rewards": [
    { "type": "tasked:item", "item": "minecraft:wooden_axe" }
  ]
}
```

## Identity

| Field | Meaning |
|---|---|
| `id` | Lowercase letters, digits and underscores. The name progress is stored under — see the rename note on [[tasked:quest-files]]. |
| `title` | What the player sees, under the node and at the top of its card. |
| `subtitle` | One line beneath the title. Optional. |
| `description` | Paragraphs, read as markdown — see below. |
| `icon` | An item, as `{ "item": "minecraft:oak_log" }`. Defaults to paper. |
| `aliases` | Former ids, so a rename does not orphan progress. |

Chapter groups and chapters have ids, titles, subtitles and icons too, and the same rules apply to
them.

## Writing a description

A description is an array of strings, one per paragraph, and each is read as **markdown** when a
player opens the quest:

- `**bold**`, `*italic*` (or `_italic_`), and `` `code` ``.
- `[text](https://example.com)` for a link — underlined, and it opens in the browser when clicked.
  Only `http` and `https` open; anything else is refused with a word in the status bar.
- A line starting with `#`..`######` is a heading, and one starting with `- `, `* ` or `+ ` is a
  bullet. Headings come in four sizes: `#` is twice the body text, then `##` at 1.75×, `###` at 1.5×
  and `####` at 1.25×. `#####` and `######` use the smallest of those, because the card's font has one
  size and the body's is its floor.
- A backslash escapes the character after it, so `\*` is a literal star.

A line break is a line break: markdown here does not join lines into flowing paragraphs, because these
files are written a line per paragraph and joining them would re-wrap prose an author had already
wrapped. The file keeps the raw markdown — the editor shows it as written, and only the reader's card
renders it.

The same reading applies to a chapter's and a group's description. The editor always shows text that a
reader would not yet see, which is the point of the `hideTextUntilComplete` flag below.

## The node on the canvas

| Field | Default | Meaning |
|---|---|---|
| `x`, `y` | `0` | Position on the canvas, in canvas units rather than pixels, so the layout survives a window resize. Grid spacing is 32. |
| `shape` | `rounded` | The outline: `rounded`, `square`, `circle`, `diamond`, `hexagon`, `octagon`, `pentagon`, `gear`, `heart`, `tome`, or `none`. |
| `size` | `48` | The node's width and height, 16–512 pixels. |
| `rotation` | `0` | Degrees clockwise. Any shape can be turned; a full turn is written as `0`, because it is the same shape. |
| `iconScale` | `0.75` | How much of the largest square that fits the node the icon fills. `1.0` is corner to corner; the default leaves a margin so the shape reads. |
| `showTitle` | `false` | Draw the quest's name under its node. Off by default, because a canvas of fifty names is a wall of text — the name is on hover either way. |

A shape of `none` draws no panel at all: the node is its icon, and the whole square is clickable. Every
other outline is hit-tested by its own pixels, so a click lands on exactly what is drawn.

A quest's coordinates are its own; a [[chapter]] is never positioned. Its [[frame]] on the canvas is
computed from the quests inside it plus padding, so moving a quest moves the frame with it.

## Dependencies

`dependsOn` is a list of quest ids — or their aliases — that must be satisfied before this one is
open. They may live in other files, and a reference that resolves to nothing is an
[[tasked:validation|error rather than a quiet lock]]. A [[chapter]] whose `progressionMode` is
`linear` supplies the chain itself, so its quests need no `dependsOn` at all.

| Field | Default | Meaning |
|---|---|---|
| `dependsOn` | — | Quest ids or aliases this quest waits on. |
| `prerequisiteMode` | the chapter's | What the dependencies have to reach: `all_completed`, `one_completed`, `all_started`, `one_started`. This is the [[progression-mode]] axis — how a set of prerequisites is treated. |
| `minRequired` | `0` | How many dependencies must be satisfied, replacing the mode's own count. This is how "any three of these five" is written. May not exceed the number of dependencies. |
| `dependencyLines` | — | Per-line overrides for the canvas, keyed by the dependency's id. |

`minRequired` replaces the *count*, not the *bar*: the mode still decides what each one must reach, so
`one_started` with `minRequired: 2` means "any two of these, started".

### Drawing the lines

The chapter's `dependencyStyle` sets how its lines look; `dependencyLines` overrides one dependency's
line, axis by axis. Only the axes a line names are overridden — the rest fall back to the chapter,
then to the built-ins (`orthogonal`, `one`, `solid`, `thin`).

| Axis | Values | Meaning |
|---|---|---|
| `form` | `orthogonal`, `straight`, `curved` | The route's shape: a three-segment step, a direct line, or a smooth bow that tells parallel routes apart. |
| `arrows` | `none`, `one`, `both`, `many` | Where arrowheads are drawn. `one`, at the dependent end, is the default. |
| `dash` | `solid`, `dashed` | Whether the line is drawn in runs or unbroken. |
| `weight` | `thin`, `thick` | How many pixels wide. |
| `bend` | −0.8–0.8 | How far a curved line bows, as a fraction of the chord between its ends. |
| `fromAnchor` / `toAnchor` | degrees | The angle a line leaves or meets a node at, 0 east and growing clockwise. Per line only, because which rim a line meets is a fact about its two ends. |
| `fromHandle` / `toHandle` | `[along, across]` | A split line's control points. They are written together — one alone is a split with half a shape. |

For a whole chapter:

```json
{
  "dependencyStyle": { "form": "curved", "arrows": "one", "dash": "solid" }
}
```

## Progression and repeats

| Field | Default | Meaning |
|---|---|---|
| `repeatable` | `false` | Completable more than once. `timesCompleted` survives each completion, and something depending on it stays satisfied. |
| `repeatCooldownTicks` | `0` | Ticks to wait between completions. 2400 is two minutes. |
| `sequentialTasks` | `false` | Tasks must be handed in in order: the second cannot be handed in until the first is. |
| `exclusiveGroup` | — | Quests sharing a name are mutually exclusive: completing one locks the others, permanently. A [[exclusive-group]] is a choice of paths. Scoped to the chapter. |
| `maxCompletableDependents` | `0` | At most this many of the quests depending on this one may complete; the rest stay locked for good. `0` is no cap. A dependent already completed stays completed. |

The chapter's own `progressionMode` decides whether the chapter is walked one quest at a time or
unlocked as it becomes available:

- `flexible` — every quest whose prerequisites are met is open. The default.
- `linear` — the order of the chapter's `quests` list *is* the progression: each unlocks when the one
  above it completes.

A quest in a linear chapter that also declares `dependsOn` must satisfy both its own edges and the
quest above it. That is a real combination — a linear spine with one quest that also needs something
from another chapter — so nothing warns about it.

## Visibility

Seven flags, each hiding one different thing. All of them are presentation: a hidden quest still
loads, still counts for progress, and is always shown to the editor.

| Field | Default | Hides |
|---|---|---|
| `invisible` | `false` | The whole quest, until it is completed. |
| `invisibleUntilTasks` | `0` | With `invisible` set: also unhide once this many tasks have any progress. The easter-egg case — a quest nobody can see until they stumble onto part of it. Without `invisible` it does nothing. |
| `hideUntilDependenciesComplete` | `false` | Until the prerequisite *rule* is satisfied — the same rule the card's "2 of 3 met" counts, so `minRequired` and the started-based modes are honoured. |
| `hideUntilDependenciesVisible` | `false` | Until at least one prerequisite is itself visible. Recursive, so a chain reveals itself one link at a time from its first visible end. |
| `hideDependencyLines` | `false` | The lines arriving at this quest. The quest itself is unaffected, and quests that depend on it still draw their lines to it. |
| `hideTextUntilComplete` | `false` | The description, until the quest is completed — for a quest whose text would give away what it asks for. |
| `hideDetailsUntilStartable` | `false` | Task and reward details, until the quest can be started. The prerequisites stay: they are what tells the reader how to unlock it. |

## Stages

`requiresStage` is a namespaced id the player must have for the quest to be open to them:

```json
{ "requiresStage": "my_pack:inducted" }
```

It is the one gate here that is **per player rather than per team**: a quest gated on a stage is open
to a player who has it and locked to one who does not, even in the same party. Stages are granted by
[[tasked:rewards|stage rewards]] and removed by them, read by stage tasks, and manipulated from
scripts — see [[tasked:kubejs]].

> [!WARNING]
> Nothing validates that a stage exists, because a stage exists by being granted: there is no list to
> check against. A typo is therefore not reported — it shows up as a quest nobody can ever open.

## What a quest inherits

These live on the chapter manifest and apply to its quests unless a quest overrides them:

| Field | Default | Meaning |
|---|---|---|
| `defaultPrerequisiteMode` | `all_completed` | The `prerequisiteMode` a quest uses unless it says otherwise. |
| `defaultConsumeItems` | `false` | Whether item tasks in this chapter take the items unless the task says otherwise. An author sets it once for a whole trade chapter. |
| `dependencyStyle` | built-ins | The drawing defaults for the chapter's lines. |
| `theme` | — | A palette the chapter asks to be drawn in. A client concept: the catalogue lives on the client, so the name is a plain string here, and a client that cannot resolve it says so. |
