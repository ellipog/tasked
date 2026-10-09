# FTB Quests mapping

Every FTB Quests 1.21.1 field below already has a Tenet home: the middle column is what the
migration tool writes, with no Tenet change needed. This page is the tool's contract for the
already-mapped half of the format — the fields that still need Tenet work live in the migration
plan, not here, and anything on this page that stops decoding breaks [[tenet:authoring/tasks]],
[[tenet:authoring/rewards]] and [[tenet:authoring/quests]] along with it.

The rule throughout: FTB's snake_case becomes Tenet's camelCase, and an FTB field that is absent
keeps Tenet's default rather than gaining a new one. The test that pins this page is
`FtbMappingTest` — one row per mapping, each decoded through the real codec, so a field that
quietly becomes required fails there first.

## Tasks that map 1:1

| FTB task | Tenet type | Notes for the tool |
|---|---|---|
| `advancement` + `criterion` | `tenet:advancement` (`advancement`, `criterion`) | An empty `criterion` (`""`, which is all 54 ATM10 uses) means the whole advancement: emit the task **without** `criterion` rather than with an empty string. |
| `observation` (`to_observe`, `timer`, `observe_type`) | `tenet:observation` (`toObserve`, `timer`, `observeType`) | Names carry over: `block`, `block_tag`, `block_entity`, `entity_type` and friends are the same words. NBT appended to `toObserve` (e.g. `minecraft:sheep{Color: 4b}`) is a fuzzy subset match on both sides. |
| `stat` (`stat`, `value`) | `tenet:stat` (`stat`, `value`) | Same two fields, same meaning. |
| `kill` (`entity`, `entity_type_tag`, `value`, `custom_name`, `nbt_filter`) | `tenet:kill` (`entity`, `entityTypeTag`, `value`, `customName`, `nbtFilter`) | The tag takes precedence over the id on both sides; NBT is a fuzzy subset match on both sides. |
| `location` (box + `ignore_dim`) | `tenet:location` (box + `ignoreDimension`) | Same box, same flag. |
| `item` + `match_components` | `tenet:item` + `match` | The three words are identical: `none`, `fuzzy`, `strict`, with `strict` the default on both sides. A 1.20.1 pack's `match_nbt` becomes `match`. |
| `item` + `only_from_crafting` | `tenet:item` + `onlyFromCrafting` | Same flag, with one documented difference: Tenet counts lifetime crafted statistics rather than watching the crafting event, so handing the stack away does not un-count it. |
| `item` + `consume_items` | `tenet:item` + `consumeItems` | Absent defers to the chapter's `defaultConsumeItems` on both sides (see below for the file-level default). |

## Rewards that map 1:1

| FTB reward | Tenet field | Notes for the tool |
|---|---|---|
| `auto` (`enabled`, `invisible`, `no_toast`, `disabled`) | `auto` on every reward | The four words plus the default are identical, including the file-level default (`default_autoclaim_rewards` → `defaultAutoClaim`). `invisible` and `no_toast` are the same silence under two names on both sides. |
| `team_reward` (+ file `default_reward_team`) | `team` (+ `defaultTeamReward`) | Absent defers to the file default on both sides. |
| `exclude_from_claim_all` | `excludeFromClaimAll` on every reward | Same flag. FTB forbids changing it on loot, random and choice rewards; keep that behaviour in the tool. |
| `ignore_reward_blocking` | `ignoreRewardBlocking` on every reward | Same flag. |
| `command` + `silent` | `tenet:command` (`command`, `permissionLevel`, `silent`) | Same flag; placeholders (`{p}`, `{x}`/`{y}`/`{z}`, `{quest}`, `{chapter}`, `{team}`) carry over. `feedback_message` is **not** mapped — it needs Tenet work. |
| `item` + `only_one` | `tenet:item` + `onlyOne` | Same flag: checked by item type, ignoring components, on both sides. Table entries keep `weight` and `randomBonus` as written. |

## Quest, chapter and file rows that map 1:1

| FTB field | Tenet field | Notes for the tool |
|---|---|---|
| `require_sequential_tasks` (quest) | `sequentialTasks` (quest) | Same flag. The **chapter** default has no Tenet home yet — inline the resolved value onto each quest until one exists. |
| `hide_quest_until_deps_complete` | `hideUntilDependenciesComplete` | Absent defers to the chapter default on both sides; `false` forces the quest visible. |
| `hide_quest_until_deps_visible` | `hideUntilDependenciesVisible` | Same tristate behaviour. |
| `hide_dependency_lines` | `hideDependencyLines` | Incoming lines only; hiding the outgoing side needs Tenet work. |
| `hide_text_until_complete` | `hideTextUntilComplete` | Same flag. |
| `hide_details_until_startable` | `hideDetailsUntilStartable` | Same flag. |
| `invisible` (+ `invisible_until_tasks`) | `invisible` (+ `invisibleUntilTasks`) | Same pair, same counting rule. |
| chapter `default_hide_dependency_lines` etc. | `defaultHideUntilDependenciesComplete`, `defaultHideUntilDependenciesVisible` | The chapter defaults for the two tristate flags above. |
| chapter `default_consume_items` | `defaultConsumeItems` | The chapter default for item tasks. The **file** default (`default_consume_items` in `data.snbt`) has no Tenet home yet — inline the resolved value onto each chapter until one exists. |
| file `detection_delay` | `detectionDelay` | Same field: the minimum ticks between inventory checks, flooring item, item-tag and fluid tasks. |

## Deliberately unmapped here

`optional` (quest), `flexibleProgress`, task/reward titles and icons, chapter images, quest
links, `min_width`, `autofocus`,
`hide_dependent_lines`, `disable_toast`, claim-timed repeats, the energy task, the manual-only
flag, filter expressions, the toast and currency rewards, team stages, progress-mutating command
variants, file settings, presets and chapter appearance defaults, and ghost validation are all
**not** on this page: each needs Tenet work first, and the tool must gate on that work rather
than emit fields nothing reads.
