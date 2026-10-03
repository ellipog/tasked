# Tasks

A quest's `tasks` array is what it asks for. Every task must be satisfied unless it is marked
`optional` — and if they are all optional, any single one of them satisfies the quest, because a quest
where nothing at all is required would complete itself the instant it unlocked.

Every task object carries its `type` and that type's fields, flat at the same level:

```json
{ "type": "tasked:item", "item": "minecraft:oak_log", "count": 8 }
```

A field that belongs to another type is a mistake the [[tasked:authoring/validation|validator]] reports rather
than a value anything reads. The list of what this build has — and every field each type takes — is
also one command away:

```cmd
/tasked types
```

## Every task has these

| Field | Default | Meaning |
|---|---|---|
| `optional` | `false` | This one does not have to be done for the quest to complete. |
| `autoSubmitTicks` | the type's own cadence | How often the task is re-checked, in ticks — twenty to the second. A type picks a sensible interval for its own cost; raise it for an expensive check. |

## What a player has to do, and what just happens

A task that takes nothing completes itself the moment its count is met: gather eight logs and the
quest ticks over. A task that takes something waits for the player to press **Submit**, because the
press is the consent to take it — an item task with `consumeItems`, an `xp` task, and a `fluid` task.
A `checkmark` is nothing but the button.

Task ids are namespaced, so the built-ins live under `tasked:` and a mod's own type is its own id. The
types below are the fifteen this build ships.

| Type | Counts |
|---|---|
| `tasked:advancement` | An advancement earned, or one criterion of one. |
| `tasked:biome` | Standing in a biome, or any biome of a tag. |
| `tasked:checkmark` | Nothing — it is a button the player presses. |
| `tasked:custom` | Whatever a registered handler answers. |
| `tasked:dimension` | Being in a dimension. |
| `tasked:fluid` | Fluid handed over, carried in buckets. |
| `tasked:item` | Carried items, with matching and consume rules. |
| `tasked:item_tag` | Carried items belonging to an item tag. |
| `tasked:kill` | Mobs killed, filtered by id, tag, name or SNBT. |
| `tasked:location` | Being inside a box. |
| `tasked:observation` | Looking at a block or an entity for a moment. |
| `tasked:stage` | Having a stage. |
| `tasked:stat` | A vanilla statistic's value. |
| `tasked:structure` | Being inside a structure. |
| `tasked:xp` | Experience handed over. |

## `tasked:advancement`

| Field | Meaning |
|---|---|
| `advancement` | The advancement's namespaced id, e.g. `minecraft:story/mine_stone`. |
| `criterion` | One criterion of it instead of the whole advancement. Absent means the whole one. |

## `tasked:biome`

| Field | Meaning |
|---|---|
| `biome` | The biome to stand in. A `#` tag counts any biome of it, e.g. `#minecraft:is_forest`. |

## `tasked:checkmark`

| Field | Meaning |
|---|---|
| `title` | The label on the button the player presses. |

The simplest way to say "read the sign", "talk to the NPC", "make the choice" — anything the game
cannot see.

## `tasked:custom`

| Field | Meaning |
|---|---|
| `id` | The id a handler was registered under: by a mod during its construction, or by a script — see [[tasked:authoring/kubejs]]. |
| `value` | The number the handler's answer has to reach. |

The handler answers how far along the player is, and the engine treats that answer exactly as it
treats a stat's or a kill count's. Nothing outside a handler can advance a task, so a custom task is a
measured task or it is nothing.

A `tasked:custom` task whose handler is not registered is not an error: the quest still loads and the
validator warns that this build has nothing registered for the id — which is what a pack shipping a
handler mod as an optional dependency wants to see.

## `tasked:dimension`

| Field | Meaning |
|---|---|
| `dimension` | The dimension the player has to be in, e.g. `minecraft:the_nether`. |

## `tasked:fluid`

| Field | Meaning |
|---|---|
| `fluid` | The fluid's namespaced id, e.g. `minecraft:water`. |
| `amount` | How much of it, in millibuckets. A bucket is a thousand. |

Carried in the buckets the player holds — a submit empties them and hands the empties back. FTB
Quests fills this task at a task screen block; Tasked has no such block, so this is the
carried-container path for now.

## `tasked:item`

| Field | Default | Meaning |
|---|---|---|
| `item` | — | The item's namespaced id. |
| `count` | `1` | How many. |
| `components` | — | 1.21 data components, keyed by component id in the datapack's own spelling. |
| `match` | `strict` | How closely a carried stack must match: `none` ignores its data, `fuzzy` needs the data this task names, `strict` needs the whole stack. |
| `consumeItems` | the chapter's `defaultConsumeItems` | Whether handing it in takes the items. |
| `onlyFromCrafting` | `false` | Only items the player crafted themselves count. |

`components` is how a task asks for a *renamed* sword rather than a plain one: a text component's
value is a string holding JSON, as the codec writes it, and the editor writes that form when you pick
a renamed item:

```json
{
  "type": "tasked:item",
  "item": "minecraft:diamond_sword",
  "components": { "minecraft:custom_name": "\"Tempered Blade\"" }
}
```

An item this build does not have keeps its id and the quest still loads: the row draws a placeholder
and says the item is missing, so a removed mod can come back.

## `tasked:item_tag`

| Field | Default | Meaning |
|---|---|---|
| `tag` | — | An item tag, without a leading `#`, e.g. `minecraft:logs`. Any item in it counts. |
| `count` | `1` | How many. |
| `consumeItems` | the chapter's default | Whether handing it in takes the items. |

A sibling of `tasked:item` rather than a field on it, so a tag where an id is expected is an error
here as it is everywhere else.

## `tasked:kill`

| Field | Meaning |
|---|---|
| `entity` | The mob's namespaced id, e.g. `minecraft:zombie`. |
| `entityTypeTag` | Or an entity tag, to count any mob in it. Its own field rather than a `#` in `entity`. |
| `value` | How many kills count as enough. |
| `customName` | Only count a mob with this name. |
| `nbtFilter` | An SNBT filter, for the mobs an id cannot pick out. |

## `tasked:location`

| Field | Meaning |
|---|---|
| `position` | One corner of the box, in blocks, as `[x, y, z]`. |
| `size` | How far the box reaches from that corner, as `[x, y, z]`. |
| `dimension` | The dimension the box is in. |
| `ignoreDimension` | Count the box anywhere, instead of only in the dimension above. |

## `tasked:observation`

| Field | Default | Meaning |
|---|---|---|
| `observeType` | — | What counts as looking: `block`, `block_tag`, `block_state`, `block_entity`, `block_entity_type`, `entity_type`, or `entity_type_tag`. |
| `toObserve` | — | The block or entity to look at, following `observeType`. |
| `timer` | `20` | How long to look at it, in ticks. |

## `tasked:stage`

| Field | Meaning |
|---|---|
| `stage` | The stage the player has to have, e.g. `my_pack:left_the_village`. |

The read half of the same feature whose write half is a stage [[tasked:authoring/rewards|reward]], a command, or
a script: a pack grants a stage somewhere and asks about it here. Nothing is required of the id — a
stage exists by being granted — so a typo shows up as a task that never completes rather than as a file
that will not load.

## `tasked:stat`

| Field | Meaning |
|---|---|
| `stat` | Which vanilla statistic to watch, e.g. `minecraft:walk_one_cm`. |
| `value` | The value that counts as enough. |

## `tasked:structure`

| Field | Meaning |
|---|---|
| `structure` | The structure to be inside, e.g. `minecraft:village_plains`. A `#` tag counts any of them. |

## `tasked:xp`

| Field | Default | Meaning |
|---|---|---|
| `value` | — | The number of points or levels, up to 100 000. |
| `points` | `true` | `true` counts experience **points**; `false` counts whole **levels**. |

Manual only, deliberately: experience is not something the engine should take the moment a player
happens to be carrying enough — the Submit press is the consent, and the press takes it.
