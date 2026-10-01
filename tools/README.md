# tasked/tools

Tools and content that belong to the Tasked **repository** rather than to the mod.

Nothing in this folder is compiled, and nothing in it ships in the jar.

## `quests/` — the worked examples

Seven complete questlines, and the reason they are here rather than in `src/main/resources` is worth
stating: **Tasked ships no quests.** It is a quest engine, and a mod that installs example chapters
into every player's config directory has made a decision that is not its to make. The first thing a
pack author would have to do is delete somebody else's content, and every file a mod ships is a file
that has to keep working forever against a format that is still moving.

So the examples are authoring documentation, and they arrive in a `config/tasked/quests` directory
because somebody ran the script below.

They are seven different *designs* rather than seven difficulties of the same thing, and they run
from five quests to ninety:

| Group folder | What it is for | Quests |
|---|---|---|
| `getting_started/` | The basics: one chain, a couple of item tasks that do not consume, one checkmark. The one to read first, and the one the playthrough test plays. | 5 |
| `the_road/` | Linear progression with a name under every node, so a chapter where the list order *is* the progression is walked rather than described. Six stops out, six back, and the canvas draws the road. | 12 |
| `the_trade/` | The mechanics, one per quest: a branch, an OR-gate, an exclusive pair, a repeatable job with a cooldown, sequential tasks, an optional task, a hidden quest, an alias, a slow check and a count of 512 — plus a second chapter whose whole lesson is one inherited default and the one task that declines it. | 17 |
| `the_council/` | Four prerequisite modes side by side: four quests waiting on the same three trials under four different rules, and a fifth that does nothing but inherit the chapter's. Holds the only dependency in the collection that leaves its own group. | 10 |
| `the_constellation/` | A canvas with no grid in it: twenty-four nodes placed by hand to draw a serpent in the sky, most of them sixteen pixels across, with one 224-pixel moon. The extreme of position, size and icon scale. | 24 |
| `the_mosaic/` | A picture made of quest nodes: forty-six tiles on a 48-pixel grid forming a heart, filled in by a LINEAR chapter's own list order. The largest single chapter and the one to look at rather than read. | 46 |
| `theme_gallery/` | One chapter per shipped UI theme **apart from `default`** — fifteen of them, six quests each — with **identical geometry** and different content, so clicking between chapters shows what the theme changed. See below. | 90 |

`QuestIndexTest` asserts all of it — every mechanism, all four prerequisite modes, aliases at all
three levels and a dependency that resolves through one, the extremes of size and icon scale, a
cross-group edge, the linear chapter of forty-odd nodes, and that the sizes really do run from a
handful to a scroll — so a future tidy-up cannot quietly turn an exhibition into seven copies of the
first file.

Two of the seven are meant to be looked at rather than read: `the_mosaic` is a heart you colour in,
and `the_constellation` is a sky. `/tasked complete <quest>` walks either of them a node at a time,
which is how both are meant to be seen.

### Where to look for a particular field

Every field the format has is exercised somewhere, and this is the index from "I want to see one" to
the file that has it:

| If you want to see… | read |
|---|---|
| an item task that counts itself, without consuming | `getting_started/first_steps/punch_a_tree.json` |
| a checkmark you hand in by hand | `getting_started/first_steps/read_the_sign.json` |
| a linear chapter, with no `dependsOn` anywhere | `the_road/desert_road/` |
| `showTitle`, and shapes and sizes used as drawing | `the_road/desert_road/` — `a_little_green.json` and `a_landmark.json` are the two extremes |
| an OR-gate (`minRequired`) | `the_trade/toolsmith/build_all_the_things.json` (one of two) and `the_council/the_round_table/the_two_witnesses.json` (two of three) |
| an exclusive pair | `the_trade/toolsmith/specialise_blade.json` and its sibling |
| repeatable, with a cooldown | `the_trade/toolsmith/tend_the_forge.json` |
| sequential tasks | `the_trade/toolsmith/the_long_road.json` |
| an optional task | `the_trade/toolsmith/optional_curiosity.json` |
| a quest hidden until it is done | `the_trade/toolsmith/the_hoard.json`, and the last tile of `the_mosaic/the_heart/` |
| an alias, and a dependency written against one | `the_trade/toolsmith/smelt_iron.json` declares it; `forge_a_hammer.json` depends on it |
| `autoSubmitTicks` | `the_trade/toolsmith/the_apprentice.json` |
| a count big enough that the bar is the point | `the_trade/toolsmith/the_storeroom.json` |
| a text written as a translation key | the title of `the_trade/toolsmith/the_storeroom.json` |
| `defaultConsumeItems`, inherited and then declined | `the_trade/the_shop/chapter.json`, `a_favour_for_a_friend.json` |
| all four prerequisite modes | `the_council/the_round_table/` — the four nodes in the lower row |
| `defaultPrerequisiteMode` | `the_council/the_round_table/chapter.json` |
| a dependency in another chapter group | `the_council/the_round_table/the_outsider.json` |
| a chapter alias and a group alias | `the_council/the_round_table/chapter.json`, `the_council/group.json` |
| a group collapsed by default | `theme_gallery/group.json` |
| the smallest node there is, and the largest | `the_constellation/the_wyrm/the_ember.json` (16px) and `the_moon.json` (224px) |
| `iconScale` at both ends | the same two files (1.0 and 0.3) |
| a chapter wearing a theme of its own | any chapter with a `theme` field; the gallery is the fifteen that exist to be compared |
| a picture made of positions | `the_mosaic/the_heart/` — and `the_constellation/the_wyrm/` for one drawn freehand |

### The layout, which is the format

A questline is a **folder tree**, not one file per questline. There is no `01_stone_age.json` any
more and there is no `version` field to write:

```
quests/
  getting_started/            a group. Its folder name IS its id.
    group.json                title, and the chapter folders in order
    first_steps/              a chapter. Folder name is its id too.
      chapter.json            title, and the quest file names in order
      punch_a_tree.json       one whole quest per file
      make_a_table.json
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

### The theme gallery, and why it is built the way it is

`theme_gallery/` is the odd one out and the only example whose *design* is about something
other than quest mechanics. Its fifteen chapters — `gallery_modern`, `gallery_tome`,
`gallery_vanilla_plus`, `gallery_high_contrast`, `gallery_monochrome`, `gallery_paper`,
`gallery_obsidian`, `gallery_amethyst`, `gallery_copper`, `gallery_redstone`, `gallery_nether`,
`gallery_end`, `gallery_deep_dark`, `gallery_terminal`, `gallery_neon` — are the same questline in
fifteen dressings, six quests each, and their layout is identical quest for quest: same `x`, same `y`,
same `shape`, same `size`, same `iconScale`. Only the text and the icons differ.

Each chapter names a theme in its **`theme`** field, which is what makes the file work:

```json
{ "id": "gallery_tome", "title": "Tome", "theme": "tome", "quests": [ ... ] }
```

**A chapter's theme covers the canvas and the quest overlay, and nothing else.** The sidebar, the
header, the title, the buttons and the tooltips are drawn in *your* theme — a setting, kept in
`config/armature/appearance.json`, and not one of these chapters at all. Standing in a violet chapter
shows a violet canvas inside an otherwise unchanged book, and both are visible in one frame with no
precedence rule between them.

That split replaced a design that got it wrong. A chapter's theme used to be an *override* of the
player's: it won while you stood there, a click declined it, the decline lasted one visit, and two
flags existed to remember that. It worked, and it is gone, because "how do I want this program to
look" and "what does this chapter look like" turned out to be different questions about different
regions of the screen rather than two answers to one question. The region is `ArmatureTheme.scope`;
the chapter's colours are a `ThemePatch` over the player's, so a theme and a chapter do not compete
and neither has to know the other exists.

**The book itself is drawn in the `default` theme**, which is not one of these fifteen: it is Modern's
palette with square corners, and `Modern` in the catalogue keeps the rounded corners it has always
had. That is why the chapter named Modern is worth opening first — it has the same colours as the frame
around it and one value different, so the corner radius is the only thing that moves.

There were two controls at the foot of the sidebar, a theme picker and a motion switch. Both are gone
from the book: they are dev-mode tools now, the picker beside the theme editor it belongs with and the
motion switch beside the accessibility settings it duplicates. A theme is a setting, and a setting that
sits permanently under a chapter list in every book is one that was never quite in the right place.

That is also why the geometry is copied between all fifteen chapters and nothing else is. If they
differed in layout as well as palette, a difference you saw while clicking between them could be the
theme or the content, and an exhibit that varies two things demonstrates neither. **The rule for
editing this file is: geometry is copied between chapters, content is not.** The test asserts all of
it — every chapter's geometry list equal to the first, all ninety titles distinct, at least thirty
distinct icons, fifteen distinct theme names — so breaking any of it fails the build rather than
showing up as a confusing screenshot.

Each chapter also ends with a long description, which is there to make the body taller than its clip
so the scrollbar appears. That is now one of the more interesting things to compare, because the
scrollbar has tokens of its own as of this round: it used to borrow the raised surface and a control
edge, neither of which was chosen for the job, so every theme got the same weak contrast between a
track and the panel behind it. Fifteen chapters is fifteen scrollbars, from High Contrast's white on
black to Copper's deliberate near-invisibility. If a chapter's scrollbar stops appearing, that
paragraph got shorter and everything it was demonstrating went with it.

The themes' **corner radii** differ — Tome and End are 8, Amethyst and Neon 6, Modern and the four
written from it 4, Obsidian and Monochrome 2, and Terminal, Vanilla Plus and High Contrast 0 — and the
radius is drawn, so it is one of the things to look at.

It shows up in two places, and comparing them is the clearest demonstration of the region split in the
whole gallery. **The book's own panel** — sidebar, header strip and all — is drawn in *your* theme, so
it has square corners while you are on `default` no matter which chapter you are standing in. **The
quest overlay** is inside the chapter's scope, so opening a quest rounds its panel to whatever the
chapter asks for. Tome against Vanilla Plus is the pair to flip between: the same panel, drawn by the
same code, at 8 and at 0.

The radius is also the value that was *advertised but not drawn* until this round — every theme set
one and nothing read it — so if you find a corner that is still square in a theme that says otherwise,
it is a call site that has not been converted, and it is worth reporting. The interior surfaces of a
panel are the ones most likely to be missed, because a panel rounds while the recessed area inside it
does not.

**An unknown theme name is handled rather than fatal, and it is reported.** A chapter naming a theme
this build does not have leaves the player's own theme in force and logs a line naming the chapter and
the name. It does not refuse to open the chapter — one bad string in a quest file must not cost a
player their quests. `QuestIndexTest.exampleThemesExist` checks the shipped examples against the
built-ins, so a typo in this file fails the build rather than reaching a player's log.

**The corner radius changes too.** Every theme carries one and they really do differ — `tome` and
`end` are 8, `amethyst` and `neon` 6, `modern` and the four written from it 4, `obsidian` and
`monochrome` 2, and `terminal`, `vanilla_plus` and `high_contrast` 0 — and the book's own panel, its
sidebar, its header strip and the quest overlay are all rounded to whatever the theme says. The inner
surfaces round only the corners they share with the panel, so the gap between the two curves stays
even.

Until this round the radius was *advertised but not drawn*: every theme set one and nothing read it,
so a radius of 8 and a radius of 0 rendered identically. If you find a corner that is still square in
a theme that says otherwise, it is a call site that has not been converted and it is worth reporting.
The interior surfaces of a panel are the ones most likely to be missed, because a panel rounds while
the recessed area inside it does not.

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

A newly-added example is **created** rather than kept, so adding `theme_gallery/` reached every
profile and combo on the next `--workspace` run without `--force`. `--force` is only needed to
*replace* files that were already there.

## The one thing it deletes, and why

Converting an install from the old flat format to folders means the same questline exists **twice** —
once as `01_stone_age.json`, once as `getting_started/`. That is worse than a stale copy. It is two
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
