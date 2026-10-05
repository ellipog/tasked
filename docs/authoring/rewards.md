# Rewards

Rewards are what a quest gives when it completes. They are separate from [[task]]s, evaluated once,
and by default they wait for the player to claim them — the claim button on a finished quest's card,
the **Rewards** inbox, where each waiting quest folds open into its own rewards and every row has its
own **Claim**, or **Claim all** for everything outstanding.

```json
{ "type": "tasked:item", "item": "minecraft:diamond", "count": 4 }
```

## Every reward has these

| Field | Default | Meaning |
|---|---|---|
| `team` | the tree's `defaultTeamReward` | One claim for the team, rather than one per player. |
| `auto` | `default` | When it is handed over. See below. |
| `excludeFromClaimAll` | `false` | Claim all leaves this one for its own press. |
| `ignoreRewardBlocking` | `false` | Give it even while the team's payouts are held by `/tasked rewards block`. |
| `conditions` | none | Gates the payout on the receiving player: an item, a tag, a score, an advancement, a stage, or how many of a party are online. Every entry must hold, and every path that pays checks them. A reward that is an *entry of a reward table* cannot carry them — the validator refuses it, because an entry is handed out by the roll rather than claimed; put them on the table reward. See [[tasked:authoring/conditions]]. |

`auto` decides the moment the reward changes hands:

| Value | Meaning |
|---|---|
| `default` | Follow the quest's own `autoClaim`, then the chapter's, then the tree's `defaultAutoClaim` — which defaults to `disabled`. |
| `enabled` | Give it the moment the quest completes. |
| `disabled` | Wait for a claim. |
| `no_toast` | Give it automatically, without a toast. |
| `invisible` | Give it automatically, without telling the player. |

### Turning it on for a whole chapter

The fifty dirt-and-wood quests at the start of a pack should not be fifty clicks, and they should not
be fifty settings either. A chapter turns auto-claim on once:

```json
{ "id": "first_steps", "autoClaim": "enabled", "quests": [ ... ] }
```

and a quest that wants to differ says so for itself:

```json
{ "id": "the_boss", "autoClaim": "disabled" }
```

The ladder, most specific first: a reward's own `auto` → the quest's `autoClaim` → the chapter's
`autoClaim` → the pack's `defaultAutoClaim` in `index.json` → off. `default` at any level means "ask
the level below".

Two things a mode can never override:

- **A choice reward is never auto-granted.** Its payout *is* the player's pick, so it stays
  outstanding and is offered the moment the quest is claimed — whatever `auto` says. A file that sets
  an automatic `auto` on a `tasked:choice` gets a warning saying so, because the setting would
  otherwise read as supported.
- **A held team gets nothing.** `/tasked rewards block` outranks every automatic mode; unblocking is
  the one moment it is released.

`no_toast` and `invisible` differ in what the client shows: both grant silently, and both also
suppress the completion notice for that quest, so a chapter of starter quests does not announce
itself fifty times.

While anything is still waiting, the player sees it in two places: a small badge on the quest's node
with the number of rewards, and a count beside the chapter's row in the sidebar — `The Shop (3)` —
which is where the eye goes first.

An operator can hold every automatic payout at once with `/tasked rewards block`, whatever individual
rewards say, and release it with `/tasked rewards unblock`. The switch is stored in the team's
progress rather than in a save, so it is a server-side decision and not up to the player being paid.

## The types

| Type | Gives |
|---|---|
| `tasked:advancement` | An advancement, or one criterion of one. |
| `tasked:choice` | One entry of a table, picked by the player. |
| `tasked:command` | A command, run as the player. |
| `tasked:custom` | Whatever a registered handler does. |
| `tasked:item` | Items. |
| `tasked:loot` | A table roll that can come up empty. |
| `tasked:random` | A weighted table roll that promises something. |
| `tasked:all_table` | Every entry of a table. |
| `tasked:stage` | A stage, granted or taken away. |
| `tasked:xp` | Experience, in points or levels. |

## Simple rewards

| Type | Field | Meaning |
|---|---|---|
| `tasked:advancement` | `advancement`, `criterion` | The advancement to award; `criterion` names one criterion instead of the whole thing. |
| `tasked:xp` | `amount`, `levels` | Points, or whole levels when `levels` is `true`. Default is points. |
| `tasked:stage` | `stage`, `remove` | The stage to set; `remove: true` takes it away instead of granting it. |
| `tasked:custom` | `id` | The id a handler was registered under. A reward whose handler is not registered warns rather than failing. |

## `tasked:item`

| Field | Default | Meaning |
|---|---|---|
| `item` | — | The item's namespaced id. |
| `count` | `1` | How many. |
| `components` | — | 1.21 data components, as on the item task. |
| `randomBonus` | `0` | Up to this many more, rolled at random on top of the count. |
| `onlyOne` | `false` | Skip it if the player already carries this item. |

## `tasked:command`

The escape hatch every pack reaches for, and the one reward whose reach is the whole server — so it
runs through the server's own dispatcher with the player as the source.

| Field | Default | Meaning |
|---|---|---|
| `command` | — | The command, without the leading slash. |
| `permissionLevel` | `2` | The level it runs at; 2 is a command block's. |
| `silent` | `false` | Do not say in chat that it ran. |

The placeholders are FTB Quests' own, kept whole so a pack moved from it does not have to learn a
second vocabulary for the same sentence:

| Placeholder | Becomes |
|---|---|
| `{p}` | The player's scoreboard name |
| `{x}`, `{y}`, `{z}` | The player's block position |
| `{chapter}` | The id of the chapter the quest belongs to |
| `{quest}` | The quest's id |
| `{team}`, `{team_id}`, `{long_team_id}` | The progress owner's id |
| `{member_count}`, `{online_member_count}` | How many members the party has |

An unknown brace-word is left as written rather than blanked: a command that says `{player}` to an
operator reading the log is a one-second fix, and a command that silently loses the word is a mystery.

```json
{
  "type": "tasked:command",
  "command": "say {p} finished {quest}",
  "permissionLevel": 2
}
```

## Table rewards

Four types share one shape: `tasked:random`, `tasked:loot`, `tasked:all_table` and `tasked:choice`.
Each names a table — a file under `reward_tables/`, by id and without the `.json` suffix — or carries
its own `inline`. The type *is* the mode; a file cannot turn a `random` into a `choice` by adding a
field.

| Type | What the roll does |
|---|---|
| `tasked:random` | Throws the dice `lootSize` times over the weights, and promises something: there is no empty band. |
| `tasked:loot` | The same, except the empty band exists — `emptyWeight` is the chance of nothing on a throw. |
| `tasked:all_table` | Grants every entry, no dice. |
| `tasked:choice` | Sends the entries to the player and waits for the pick. |

`random`, `loot` and `all_table` resolve the moment the reward is granted. `choice` cannot — the
player picks — so the claim marks nothing until it is answered, which also means a crash between the
offer and the pick loses nothing.

## Writing a table

A table is a list of entries, each an ordinary reward with a weight:

```json
{
  "lootSize": 2,
  "emptyWeight": 10,
  "entries": [
    { "weight": 0, "reward": { "type": "tasked:item", "item": "minecraft:bread", "count": 4 } },
    { "weight": 3, "reward": { "type": "tasked:item", "item": "minecraft:iron_ingot", "count": 8 } },
    { "weight": 1, "reward": { "type": "tasked:xp", "amount": 30 } }
  ]
}
```

| Field | Default | Meaning |
|---|---|---|
| `entries` | — | The table. Each entry is a `reward` and a `weight`. |
| `emptyWeight` | `0` | The chance of nothing on a throw, against the positive weights. Only `tasked:loot` includes it. |
| `lootSize` | `1` | How many times the dice are thrown. |
| `title` | the id | What the in-game editor calls the table. Absent, the id is opened out: `tier_1_ores` reads as "Tier 1 ores". |
| `icon` | see below | The item the editor's table browser draws for it: `{ "item": "minecraft:iron_ingot" }`, with an optional `count` and `components` — the same shape a quest's `icon` uses. |
| `uid` | — | The editor's handle for a table written `inline`. A table in its own file does not need one, and the loader ignores it there. |

An absent `icon` is not a blank row: the editor draws the first entry that has an item, so a table of
ore drops shows an ore rather than the chest every table reward type carries. A table of experience or
commands — entries with no item of their own — falls back to that entry's type icon, and an empty
table to paper.

> [!NOTE]
> `title`, `icon` and `uid` are for the editor and change nothing about a roll. They are optional, so
> every table written before them still reads, and a file never needs them to work.

A `weight` is how much of the table's probability space an entry takes; its chance is its weight over
the total. **A weight of zero means always granted** — once per roll call — which is how a table says
"and everyone also gets this", so the guaranteed entry lands even when the fashionable one misses.

> [!TIP]
> An entry's `reward` can itself be a table reward, so tables nest. Nesting is cut off past eight
> levels deep with a warning in the log, which is deep enough for a loot cascade and shallow enough
> that a table that accidentally includes itself cannot hang the server.

A table that does not resolve — a typo in `table`, or a file that failed validation — logs a warning
once and grants nothing. That is an author's mistake, not a crash.

## Editing a table in game

Nothing above has to be typed. A `tasked:random`, `tasked:loot`, `tasked:all_table` or `tasked:choice`
reward's **Table** field is a card: it shows the table's icon, its name and its entry count, and it
opens two panels.

**The browser** (`Click to select or create a table`) lists every table the pack has, searchable by
name or id, with `Edit`, `Copy` and `×` on each row and one button that makes one:

- **New table** — a file under `reward_tables/`, with the name you give it.
- **None** — clears the field, so the reward rolls no table.

A table written *inside* a reward — the format's own inline shape — still reads and still rolls, but
the panels no longer make one: a table lives in a file under `reward_tables/` and nowhere else, so
there is one way to make one and one place to look. A reward holding an inline table shows it as what
it is, without an edit path into it.

**The editor** (the `Edit` chip) is the table itself:

| | |
|---|---|
| **The header** | the title (type it — a blank title falls back to the id), the number of rolls, the entry count, the total weight, and the mode the chances are read as. |
| **The rows** | one per entry: its icon and name, its weight with a stepper, and **the chance that weight means** — `Always` for a weight of zero, a percentage otherwise, `(per roll)` when the table rolls more than once, with the chance of seeing it *at least once* on hover. |
| **The fold** | an entry's own fields, exactly as the card draws a reward's: the item (the picker, with the data of the stack you are holding), the count, and — for an entry that is itself a table — a button that opens it, with a breadcrumb back. |
| **Add item** | the item picker, appending one entry at weight 1. You can also **drag a stack in from EMI or JEI**: it lands where you drop it, with its count and its data. |
| **Add reward** | any registered reward type — experience, a command, another table — as an entry. |
| **Import inventory** / **Import chest** | one entry per distinct item you carry, or per item in the container you are looking at. It appends and never touches what is there, and tells you what it did. |
| **Undo** | the last edit to this table, and stops at the table you opened — a nested table's steps are not its parent's. |
| **Test roll ×10** | rolls the table (nesting and all) and shows what came up, without granting anything. The server rolls, because only it can see every table the first one reaches. |
| **Done** | one table back, or out of the panel. `Ctrl+Z` undoes your edits here — and stops at the table you opened. |

Two things are commands rather than buttons, because they need the crosshair and a screen captures the
mouse: `/tasked table import <id>` and `/tasked table export <id>` read and fill the container you are
looking at. See [commands](../commands.md).
