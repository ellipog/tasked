# tasked/tools

Tools and content that belong to the Tasked **repository** rather than to the mod.

Nothing in this folder is compiled, and nothing in it ships in the jar.

## `quests/` — the worked examples

Twelve complete questlines, and the reason they are here rather than in `src/main/resources` is worth
stating: **Tasked ships no quests.** It is a quest engine, and a mod that installs example chapters
into every player's config directory has made a decision that is not its to make. The first thing a
pack author would have to do is delete somebody else's content, and every file a mod ships is a file
that has to keep working forever against a format that is still moving.

So the examples are authoring documentation, and they arrive in a `config/tasked/quests` directory
because somebody ran the script below.

They are twelve different *designs* rather than twelve difficulties of the same thing — twelve
genres, each a mechanically dense questline rather than a one-mechanism exhibit, and between them
they use **every field the format has and every value of every UI enum**:

| Group folder | What it is | Quests |
|---|---|---|
| `first_light/` | The onboarding chain — five quests, no tricks. The one to read first, and the one the playthrough test plays; its five ids and mechanics never change. | 5 |
| `the_descent/` | One descent, fifteen palettes: a single story from a map room on the surface to the End and the dream of the way home, one stratum per shipped theme, the same six stations at the same coordinates in every chapter. The comparability exhibit — click between chapters and a difference you see is the theme and nothing else. | 90 |
| `the_drowned_bell/` | A cathedral under the sea. `kill` filters, a `fluid` task, `components` + `match: fuzzy` on a renamed blade, a `structure` gate, a slow `observation`, an `all_table` payout, an inline table, and a description withheld until its quest is done. | 12 |
| `the_gilded_debt/` | A counting-house: a chapter whose item tasks take what they ask for (and the one task that declines), a repeatable collection round, an OR-gate, an exclusive pair of patrons, a scoreboard the house scores its clients in, and every way a reward can be paid — once per team, picked by the player, rolled with a bonus, skipped while carried, held back from Claim all, past a payout block, or run as a command. | 20 |
| `the_long_chase/` | The great hunt: `kill` by `customName`, by `nbtFilter`, and by `entityTypeTag`; a biome named exactly; a vanilla `stat`; and the second exclusive group — two trophies, one hook. | 10 |
| `the_orrery/` | An observatory drawn in nodes, and the canvas's own exhibit: every line form, arrowhead, arrow placement, dash pattern and weight, both extremes of `bend`, anchors and split handles, a chapter `dependencyStyle` default with overrides against it, a 288-pixel sun, seven 16-pixel stars that reveal themselves one link at a time, a turned gear, a hidden line, and a planet whose lines are deliberately not drawn. | 24 |
| `the_rose_window/` | A rose window drawn by node positions and glazed in list order by a LINEAR chapter — meant to be looked at rather than read. `/tasked complete <quest>` walks it a pane at a time. | 42 |
| `the_ashen_road/` | A nether expedition: a `dimension` task, `location` boxes with `ignoreDimension`, two `structure` gates, a lava `fluid` task, a `block_entity` observation, an `advancement` task and an `advancement` reward, a quest hidden until its prerequisite rule is met, and experience paid in whole levels. | 12 |
| `the_rubric/` | A college of enchantment's rite: `requiresStage` gates, stage tasks, stage rewards that grant and remove, `custom` task and reward ids matched to the worked KubeJS script in the manual, an `advancement` task with a `criterion`, `match: strict`, `onlyFromCrafting`, a translatable title, and the group alias `the_college`. | 9 |
| `the_harvest_home/` | A village festival with the auto-claim ladder — chapter `enabled`, one quest `disabled` for itself, a reward `no_toast` and a reward `invisible` — and all six condition types across tasks and rewards, including the bring-a-friend gate. | 12 |
| `the_cartographer/` | The named road: every node titled and spaced for it, road-style lines, `location` camps, the trial chambers, a chapter alias (`the_map`), and the collection's one cross-group dependency — on `first_light`'s `the_underground`. | 13 |
| `the_midnight_market/` | A market that is only there between two and four: the whole hiding family, a crossroads that closes two of its three roads, a quest alias with a dependency written against the old name, `random` and `loot` dice with a nested table, `themePatch` over `obsidian`, and the group that starts collapsed in the sidebar. | 12 |

`QuestIndexTest` asserts all of it — the original exhibition battery, plus a second one written with
the rewrite: every task, reward and condition type, every line-art axis value, the matching family,
the kill filters, the auto-claim ladder, the payout flags, and the line extremes — so a future
tidy-up cannot quietly turn an exhibition into twelve copies of the first file.

Two of the twelve are meant to be looked at rather than read: `the_rose_window` is a window you
glaze, and `the_orrery` is a sky with line art in it. `/tasked complete <quest>` walks either of
them a node at a time, which is how both are meant to be seen.

### Where to look for a particular field

Every field the format has is exercised somewhere, and this is the index from "I want to see one" to
the file that has it:

| If you want to see… | read |
|---|---|
| an item task that counts itself, without consuming | `first_light/first_steps/punch_a_tree.json` |
| a checkmark you hand in by hand — with its own label | `first_light/first_steps/read_the_sign.json` |
| a linear chapter, with no `dependsOn` anywhere | `the_descent/the_map_room/` (and every descent chapter) |
| an `item_tag` task | `the_rubric/the_rite/matriculation.json` |
| a biome task with a tag | `the_drowned_bell/the_drowned_parish/the_road_out.json` |
| a biome task with a single id | `the_long_chase/the_hunting_grounds/the_badlands.json` |
| a dimension task | `the_ashen_road/the_far_side/the_warm_side.json` |
| a structure task | `the_drowned_bell/.../the_cathedral.json`, `the_ashen_road/.../the_fortress_wall.json`, `the_cartographer/.../the_trial_road.json` |
| a stat task | `the_long_chase/.../the_muster.json` (damage), `the_harvest_home/.../the_shepherds_tally.json` (animals bred) |
| a location box | `the_cartographer/.../the_watch_hill.json`, `the_ashen_road/.../the_shore_road.json` — both with `ignoreDimension` |
| an observation task — block, block entity, entity type, and a longer `timer` | `the_drowned_bell/.../the_lit_windows.json`, `the_ashen_road/.../the_watchtower.json`, `the_descent/the_far_shore/the_far_shore_ward.json`, `the_orrery/.../the_messenger.json`'s neighbours `the_wardens.json` (slow check + `autoSubmitTicks`) |
| a fluid task | `the_drowned_bell/.../the_flood_returns.json` (water), `the_ashen_road/.../the_bridgeworks.json` (lava) |
| an `advancement` task — whole, and by `criterion` | `the_ashen_road/.../the_blaze_road.json`, `the_rubric/.../the_enchanters_licence.json` |
| a stage task | `the_rubric/.../the_first_word.json` |
| a custom task and reward, with the script that registers them | `the_rubric/.../the_oral.json`, `the_announcement.json`, and the worked script in `docs/authoring/kubejs.md` |
| `onlyFromCrafting` | `the_rubric/.../the_apprentices_ink.json` |
| `components` + `match: fuzzy` | `the_drowned_bell/.../the_nameplate.json` |
| `match: strict` | `the_rubric/.../the_wand.json` |
| `autoSubmitTicks` on an expensive check | `the_drowned_bell/.../the_wardens.json` |
| a kill by `customName` | `the_long_chase/.../the_pale_stag.json` |
| a kill by `nbtFilter` | `the_long_chase/.../the_storm_coat.json` |
| a kill by `entityTypeTag` | `the_long_chase/.../the_grey_visitors.json`, `the_drowned_bell/.../drowned_choir.json` |
| a repeatable quest with a cooldown | `the_gilded_debt/the_ledger/collect_the_dues.json` |
| sequential tasks | `the_orrery/the_observatory_floor/the_crank.json` |
| an optional task | `the_orrery/.../the_comets_tail.json` |
| `defaultConsumeItems: true`, inherited and then declined | `the_gilded_debt/the_ledger/chapter.json`, `the_paperwork.json` |
| a consuming task declared on the task itself | `the_ashen_road/.../the_gate_stands.json` |
| an OR-gate (`minRequired`) | `the_gilded_debt/.../two_patrons.json` (two of three), `the_long_chase/.../the_chase_ends.json` (one of two) |
| an exclusive pair | `the_gilded_debt/.../patron_gold.json` and `patron_silver.json`; `the_long_chase/.../trophy_antler.json` and `trophy_hide.json` |
| a quest-level `prerequisiteMode` | `the_long_chase/.../the_chase_ends.json` (`one_completed`), `the_orrery/.../the_observatory.json` (`one_started`), `the_orrery/.../the_earth.json` (`all_started`) |
| `defaultPrerequisiteMode` | `the_gilded_debt/the_ledger/chapter.json` |
| an alias, and a dependency written against one | `the_midnight_market/the_alley/the_stall_with_no_name.json` declares it; `the_glazier.json` depends on it |
| a chapter alias and a group alias | `the_cartographer/the_survey/chapter.json` (`the_map`), `the_rubric/group.json` (`the_college`) |
| a group collapsed by default | `the_midnight_market/group.json` |
| a chapter wearing a theme of its own | `the_midnight_market/the_alley/chapter.json` (`obsidian` + `themePatch`) — and all fifteen in `the_descent/` |
| `themePatch` — a colour and a corner radius over a theme | `the_midnight_market/the_alley/chapter.json` |
| the smallest node there is, and the largest | `the_orrery/.../plough_one.json` (16px) and `the_sun.json` (288px) |
| `iconScale` at both ends | the same two files (1.0 and 0.35) |
| `rotation` | `the_orrery/.../the_turned_gear.json` |
| a picture made of positions | `the_rose_window/the_window/` — and `the_descent/` for one drawn per stratum |
| every hiding flag, one to a quest | `the_midnight_market/the_alley/` — `the_hidden_room.json` (`invisible` + `invisibleUntilTasks`), `the_fenced_goods.json` (`hideUntilDependenciesComplete`), `the_glazier.json` (`hideDetailsUntilStartable`), `the_drowned_bell/.../the_inscription.json` (`hideTextUntilComplete`), `the_orrery/.../plough_one.json` (`hideUntilDependenciesVisible`, recursive), `the_orrery/.../the_earth.json` (`hideDependencyLines`) |
| `maxCompletableDependents` — the crossroads that closes roads | `the_midnight_market/.../the_crossroads.json` |
| `requiresStage`, and the stage a reward grants | `the_rubric/.../the_first_word.json` (the gate), `the_calling.json` (the grant) |
| a stage reward that clears a flag (`"remove": true`) | `the_rubric/.../the_graduation.json` |
| an item condition gating a task | `the_harvest_home/.../the_witness_list.json` |
| a condition gating a task by tag, or by stage | `the_harvest_home/.../the_seed_cellar.json`, `the_old_flags.json` |
| a reward gated by a scoreboard objective | `the_harvest_home/.../the_dance_card.json` (made by `the_bells_rung.json`'s command reward) |
| a reward behind an advancement condition | `the_harvest_home/.../the_shepherds_tally.json` |
| a party-size condition | `the_harvest_home/.../the_festival.json` |
| a text written as a translation key with a fallback | the title of `the_rubric/.../the_calling.json` |
| an auto-claim chapter, and the quest that opts out | `the_harvest_home/harvest_week/chapter.json`, `the_wedding_feast.json` |
| `auto: no_toast` and `auto: invisible` | `the_harvest_home/.../the_tithe_barn.json`, `the_scarecrows_blessing.json` |
| a `team: true` reward | `the_gilded_debt/the_vault/the_guild_chest.json` |
| a `choice` reward | `the_gilded_debt/.../the_patrons_gift.json` |
| `randomBonus` and `onlyOne` on an item reward | `the_gilded_debt/.../the_bonus_bag.json`, `the_one_thing.json` |
| `excludeFromClaimAll` and `ignoreRewardBlocking` | `the_gilded_debt/.../the_quiet_word.json`, `the_crowns_due.json` |
| a `command` reward — loud, silent, and one that makes a scoreboard | `the_gilded_debt/.../the_crowns_due.json`, `the_quiet_word.json`, `the_first_account.json`; `the_harvest_home/.../the_bells_rung.json` |
| an `advancement` reward | `the_ashen_road/.../the_triumph.json` |
| xp paid in whole levels | `the_ashen_road/.../the_home_road.json` |
| a reward table, and a roll that can come up empty | `reward_tables/debt_dice.json`, `market_dice.json` (`emptyWeight`), `bell_toll.json` (rolled by `all_table` in `the_inscription.json`) |
| a nested table | `reward_tables/market_dice.json`, whose last entry rolls `market_dregs.json` |
| an inline table | `the_drowned_bell/.../the_road_home.json`, `the_midnight_market/.../the_market_bell.json`, `the_house_always_wins.json` |
| a cross-group dependency | `the_cartographer/.../the_road_meets_the_map.json`, on `first_light`'s `the_underground` |
| the count where the progress bar is the point | `the_harvest_home/.../the_tithe_barn.json` (256) |

And the line-art matrix — every value of every axis a line has, with `the_orrery/` as the index:

| If you want to see… | read |
|---|---|
| a chapter's `dependencyStyle` default, and the one line that accepts it | `the_orrery/the_observatory_floor/chapter.json`, `the_house_rule.json` |
| `form: orthogonal` / `chamfered` / `stepped` / `straight` / `curved` / `radial` | `the_orrery/.../the_sail.json`, `the_messenger.json`, `the_clockwork.json`, `the_wanderer.json`, `the_mirror.json`, and the chapter default |
| `dash: solid` / `dashed` / `dotted` / `dash_dot` / `double` / `hazard` | `the_orrery/.../the_crank.json`, the chapter default, `the_ringed.json`, `the_comet.json`, `the_shepherd.json`, `the_ember.json`; the cartographer's road uses `double` again |
| `weight: thin` / `thick` / `bold` / `conduit` | the chapter default, `the_wanderer.json`, `the_ember.json`, `the_messenger.json` |
| `arrowHead: chevron` / `triangle` / `dot` / `diamond` / `none` | the built-in default, `the_shepherd.json`, the chapter default, `the_mirror.json`, `the_wanderer.json` |
| `arrowPlace: target` / `both` / `mid` / `stream` | the built-in default, `the_clockwork.json`, `the_ringed.json`, `the_comet.json` |
| `arrowDensity: low` / `medium` / `high` | `the_orrery_itself.json`, `the_turned_gear.json`, `the_comet.json` |
| `bend` at both extremes | `the_mirror.json` (0.8) and `the_comet.json` (−0.8) |
| `fromAnchor` / `toAnchor` | `the_orrery/.../the_sail.json` |
| `fromHandle` / `toHandle` | `the_orrery/.../the_orrery_itself.json` |
| `hideDependencyLines` as a design decision | `the_orrery/.../the_earth.json` |
| road lines on a named road | `the_cartographer/the_survey/` — `the_old_bridge.json` (chamfered conduit), `the_milestone.json` (stepped), `the_summit_cairn.json` (double) |
| hazard tape, as a story beat | `the_ashen_road/.../the_watchtower.json` |

### The one thing the examples deliberately do not have

There is no `index.json` in `quests/`. The tree-wide settings it carries (`defaultAutoClaim`,
`defaultTeamReward`, `suppressAllAutoclaiming`) apply to **everything seeded alongside it**, and the
playthrough test seeds the whole tree — a tree-wide `defaultAutoClaim` would pay out the onboarding
quests before their claim assertions could run. The fields are documented on the
[[tasked:authoring/quest-files|quest-files page]] and in `_schema/index.schema.json`; the ladder's
levels are demonstrated chapter-down in `the_harvest_home/` and `the_gilded_debt/`.

The legacy `arrows` axis (`none`/`one`/`both`/`many`) is likewise deliberately unused in fresh
content: it is still read, for old files, but new lines write the three current axes.

### The layout, which is the format

A questline is a **folder tree**, not one file per questline. There is no `01_stone_age.json` any
more and there is no `version` field to write:

```
quests/
  first_light/                a group. Its folder name IS its id.
    group.json                title, and the chapter folders in order
    first_steps/              a chapter. Folder name is its id too.
      chapter.json            title, and the quest file names in order
      punch_a_tree.json       one whole quest per file
      make_a_table.json
  reward_tables/              named reward tables. Reserved: not a quest, read by its own pass.
    bell_toll.json
  _schema/                    editor schemas. Never loaded, never copied.
```

Three rules worth knowing before you edit anything here:

  * **A folder's name is its id**, and its manifest has to agree. Disagreement is an error naming
    both sides, because the folder name is what every path in the tree is built from.
  * **Order is declared twice, and by different things.** Group folders come in *folder-name* order
    — which means renaming a group folder can reorder the book, including which chapter you land on.
    Chapters inside a group and quests inside a chapter come from their manifest's list, so that
    order is yours and it is load-bearing: a `LINEAR` chapter's progression **is** its quest list.
  * **A file nobody lists is an error, not silence.** A chapter folder the group's `chapters` list
    does not name will never load, and the loader says so rather than skipping it quietly.

`_schema/` holds a JSON Schema per kind, so your editor autocompletes a `group.json`, a
`chapter.json` and a quest file. It is skipped by the `_` prefix rule everywhere — by the loader, by
the seeding script, and by the tests — which is the same convention the deliberately-broken fixtures
in a test combo use.

`QuestFilesTest` and `QuestFormatMigrationTest` cover all three rules, including that a chapter
written both ways — one flat file versus a folder tree — produces a **byte-identical** quest tree.

### The descent, and why it is built the way it is

`the_descent/` is the collection's theme exhibit, and its one job is to let someone switch theme and
see what changed. That only works if its fifteen chapters differ in **nothing but their palette** —
otherwise a difference on screen could be the theme or the content, and an exhibit that varies two
things at once demonstrates neither.

So the story is one descent in fifteen palettes: a map room on the surface, down through the
greenwood, the gravel gallery, the glare, the oxidation gardens, the machine vault, the resonant
halls, the black gate, the furnace dark, the silent city, the signal, the archive, the far shore,
and the dream of the way home. Each stratum is one shipped theme — every one but `default`, which is
the book's own frame rather than a chapter — and each chapter is the same six stations at the same
coordinates, in the same shapes, at the same sizes: the arrival, the path, the ward, the hazard, the
treasure, the way on. Flipping between chapters is a comparison, not a new screen.

The rule for editing it is the old gallery's rule, restated: **geometry is shared, content is not.**
Only the text, the icons and the tasks are a stratum's own. The test asserts all of it — every
chapter's geometry list equal to the first, all ninety titles distinct, at least thirty distinct
icons, fifteen distinct theme names, and every name one this build has — so breaking any of it fails
the build rather than showing up as a confusing screenshot.

Each chapter also ends with a long description on its last station, which is there to make the body
taller than its clip so the scrollbar appears — one scrollbar per theme, which is one of the more
interesting things to compare.

The themes' **corner radii** differ — Tome and End are 8, Amethyst and Neon 6, Modern and the four
written from it 4, Obsidian and Monochrome 2, and Terminal, Vanilla Plus and High Contrast 0 — and
the radius is drawn, so it is one of the things to look at. It shows up in two places: the book's
own panel is drawn in *your* theme, and the quest overlay is inside the chapter's scope, so Tome
against Vanilla Plus is the pair to flip between — the same panel, drawn by the same code, at 8 and
at 0.

**An unknown theme name is handled rather than fatal, and it is reported.** A chapter naming a theme
this build does not have leaves the player's own theme in force and logs a line naming the chapter
and the name. `QuestIndexTest` checks the shipped examples against the built-ins, so a typo in this
tree fails the build rather than reaching a player's log.

## `seed_quests.py` — how they get into a config directory

```cmd
python tasked/tools/seed_quests.py --workspace           this repo's combos and both profiles
python tasked/tools/seed_quests.py <dir> [<dir>...]      any config/tasked/quests directories
```

Created if missing. **Never overwritten unless you pass `--force`** — that directory is where an
author's own questline lives, so refreshing it automatically would destroy work. For a pristine copy
again, delete the file and re-run, or use `--force`, which names every file it replaces.

`--dry-run` says what would happen and changes nothing.

Files beginning with `_` in `quests/` are skipped, and deliberately: they are the broken fixtures the
loader ignores by that prefix, and copying one would install a questline whose only purpose is to
fail.

A newly-added example is **created** rather than kept, so a new group reaches every profile and combo
on the next `--workspace` run without `--force`. `--force` is only needed to *replace* files that
were already there — which is the case for a rewrite like this one: after replacing the exhibition,
run `--workspace --force` (or delete the old copies) so stale groups from the previous set do not
sit beside the new ones as duplicate ids.

## The one thing it deletes, and why

Converting an install from the old flat format to folders means the same questline exists **twice** —
once as `01_stone_age.json`, once as `first_light/`. That is worse than a stale copy. It is two
questlines, and every group, chapter and quest in the pair is reported as a duplicate id, naming a
file *inside a folder* as the second claimant. That message reads as a broken conversion rather than
as a file that needs deleting.

So on every run the script removes a root-level `*.json` **if and only if** every chapter-group id it
declares is also provided by a group folder that was just copied. Everything else at that level is
**kept and named, with the reason**:

```
  - 01_stone_age.json  removed: a version-1 file whose group(s) 'getting_started' are now in folders beside it
  ! mine.json  KEPT -- it declares 'my_own_group', which no copied group folder provides.
      A version-1 file beside the folders would load as a second copy of whatever it
      declares, so every shared id is reported as a duplicate. Delete it, or move it
      somewhere the loader does not read.
```

Matching on declared **ids** rather than on file names is the point. A name list would go stale, and
it would delete `01_stone_age.json` even if you had rewritten it into something of your own. A file
that will not parse is never deleted. No folder is ever deleted. `_`-prefixed files are not touched.

If you have a `mine.json` of your own, move it into a folder of its own or rename it with a leading
`_`, and the warning goes away.

## Why this is not in `.utils/`

Every other script for this project lives in `.utils/`, which is gitignored workspace tooling. This
one is different in kind: it belongs to the Tasked repository, so that a clone of Tasked alone gets
the examples *and* the thing that installs them. `.utils/Seed Example Quests.cmd` is the launcher
that runs it with `--workspace`.

(The two Python generators that wrote the big geometries — `the_descent/` and `the_rose_window/` —
are workspace tooling and live in `.utils/` as `gen_descent.py` and `gen_rose_window.py`. The files
they wrote are the content; the scripts are how you regenerate them, and the rest of the collection
is hand-written.)
