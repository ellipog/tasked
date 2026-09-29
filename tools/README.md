# tasked/tools

Tools and content that belong to the Tasked **repository** rather than to the mod.

Nothing in this folder is compiled, and nothing in it ships in the jar.

## `quests/` — the worked examples

Four complete questlines, and the reason they are here rather than in `src/main/resources` is worth
stating: **Tasked ships no quests.** It is a quest engine, and a mod that installs example chapters
into every player's config directory has made a decision that is not its to make. The first thing a
pack author would have to do is delete somebody else's content, and every file a mod ships is a file
that has to keep working forever against a format that is still moving.

So the examples are authoring documentation, and they arrive in a `config/tasked/quests` directory
because somebody ran the script below.

The four are four different *designs* rather than four difficulties of the same thing:

| File | What it is for |
|---|---|
| `01_stone_age.json` | The basics. A short chain, a couple of item tasks that do not consume, one checkmark. The one to read first, and the one the playthrough test plays. |
| `02_toolsmith.json` | The mechanics. A branch, an OR-gate, an exclusive pair, a repeatable job with a cooldown, sequential tasks, an optional task, and a quest that stays hidden until it is done. |
| `03_desert_road.json` | Linear progression with names drawn under the nodes, so a chapter where the list order *is* the progression is demonstrated rather than described. |
| `04_theme_gallery.json` | One chapter per shipped UI theme **apart from `default`** — fifteen of them — with **identical geometry** and different content, so clicking between chapters shows what the theme changed. See below. |

`QuestIndexTest` asserts all of that, so a future tidy-up cannot quietly turn four demonstrations
into four copies of the first one.

### The theme gallery, and why it is built the way it is

`04_theme_gallery.json` is the odd one out and the only example whose *design* is about something
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

A newly-added example is **created** rather than kept, so adding `04_theme_gallery.json` reached every
profile and combo on the next `--workspace` run without `--force`. `--force` is only needed to
*replace* the three that were already there.

## Why this is not in `.utils/`

Every other script for this project lives in `.utils/`, which is gitignored workspace tooling. This
one is different in kind: it belongs to the Tasked repository, so that a clone of Tasked alone gets
the examples *and* the thing that installs them. `.utils/Seed Example Quests.cmd` is the launcher
that runs it with `--workspace`.
