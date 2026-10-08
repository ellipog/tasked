#!/usr/bin/env python3
"""
stress_quests.py - generate a large synthetic pack, for measuring rather than reading.

--------------------------------------------------------------------------
Why this exists

`TESTING.md`'s baseline table has a "960-quest pack" row, and there is no such pack: the worked examples
are one chapter of seventy, which is the right size to *read* and the wrong size to *measure*. Every
question about how the canvas scales -- how fills grow with the node count, whether the edge list is
linear, what a drag costs on a big graph -- needs a graph big enough to have a shape. So this makes one.

It is generated rather than hand-written because 960 quests is not a document. It is also **randomised
within fixed bounds** (see SEED) so that a second run reproduces the first: a baseline measured on a
pack that changed between runs is not a baseline.

--------------------------------------------------------------------------
What it varies, and why each thing is varied

A stress pack that is 960 copies of one quest measures the *count* and nothing else. The canvas's cost is
a function of the shapes it draws and the lines it routes, so:

  * **Dependencies** are a randomised spanning tree plus extra cross-links, so the graph is connected,
    acyclic, and has a realistic mix of fan-in and fan-out rather than a chain.
  * **Line styles** cycle through every axis the format has -- form, arrow head, arrow place, density,
    weight, dash, and a curved `bend` -- because each draws through a different branch of the route code.
    A pack of one style measures one branch.
  * **Shapes** cycle through all twelve node outlines, and **icon scale** through its range, because the
    outline changes how much room the icon has and each is sampled at its own proportions.
  * **Prerequisite modes** vary per quest, and two chapters are `linear`, which is a different progression
    rule and a different set of lines.
  * **Icons** are drawn from a list of vanilla items, repeated: the *variety* of the sprite matters less
    than that every node has one, because an icon is a texture submission and a node without one is not.

--------------------------------------------------------------------------
Usage

    python tenet/tools/stress_quests.py <dir> [<dir>...]     write a pack into each directory
    python tenet/tools/stress_quests.py --dry-run <dir>      report what would be written
    python tenet/tools/stress_quests.py --quests 2000 <dir>  a different size

Unlike `seed_quests.py` this **replaces** its own group outright, because it is generated content and
there is nothing of yours in it to preserve. It never touches anything outside the group directory it
owns, so it is safe beside an author's own chapters.
"""

import argparse
import json
import pathlib
import random
import shutil
import sys

# ---------------------------------------------------------------- the shape of the pack

#: The default size, and **one chapter**. Splitting the same count across twelve chapters would make twelve
#: small chapters, which is the thing this pack exists not to be: every question it answers is about a
#: *canvas* -- how fills grow with the node count, whether the edge list is linear, what a drag costs on a
#: big graph -- and a chapter is the unit a canvas draws. More chapters is a different pack, so it is a flag
#: rather than the default.
CHAPTERS = 1
PER_CHAPTER = 960

#: Fixed, so a baseline taken on this pack can be retaken. Change it and you have a different pack.
SEED = 0x5EED_1234

#: The group this owns. Named so it is obvious in a directory listing that it is generated.
GROUP_ID = "stress"
GROUP_TITLE = "Stress"

#: Every node outline the format has. Cycling through them is the point -- see the module docstring.
SHAPES = ("rounded", "square", "circle", "diamond", "hexagon", "octagon",
          "pentagon", "gear", "heart", "tome", "star", "none")

#: Every line form, arrow head, arrow place, arrow density, weight and dash. Each is a different branch
#: of the route code, and a pack that uses one of each measures one of each.
FORMS = ("chamfered", "orthogonal", "straight", "curved")
ARROW_HEADS = ("chevron", "triangle", "dot", "diamond", "none")
ARROW_PLACES = ("target", "both", "mid", "stream")
ARROW_DENSITIES = ("low", "medium", "high")
WEIGHTS = ("thin", "thick", "bold", "conduit")
DASHES = ("solid", "dashed", "dotted", "dash_dot", "double", "hazard")

#: Prerequisite modes. `one_started` is the cheapest bar and `all_completed` the dearest, so the mix is
#: also a mix of how much of the graph is reachable at once -- which changes what the canvas *draws*.
MODES = ("all_completed", "one_completed", "all_started", "one_started")

#: Vanilla items, for the icon and for the item tasks. Real ids, because the loader resolves them and a
#: pack full of unknown items would be a validator error wall rather than a measurement.
ITEMS = (
    "minecraft:oak_log", "minecraft:stone", "minecraft:iron_ingot", "minecraft:gold_ingot",
    "minecraft:diamond", "minecraft:redstone", "minecraft:copper_ingot", "minecraft:coal",
    "minecraft:stick", "minecraft:torch", "minecraft:bread", "minecraft:apple",
    "minecraft:leather", "minecraft:string", "minecraft:bone", "minecraft:gunpowder",
    "minecraft:glass", "minecraft:brick", "minecraft:paper", "minecraft:book",
    "minecraft:quartz", "minecraft:amethyst_shard", "minecraft:lapis_lazuli", "minecraft:emerald",
    "minecraft:obsidian", "minecraft:flint", "minecraft:clay_ball", "minecraft:wheat",
    "minecraft:carrot", "minecraft:potato", "minecraft:honeycomb", "minecraft:slime_ball",
)

#: Task kinds to cycle through, with the fields each one actually declares.
#:
#: **Every one of these was guessed wrong once**, and it cost a 1034-file pack that loaded 98 of them:
#: `tenet:xp` takes `value` and not `amount`, and the statistic task is `tenet:stat` -- not
#: `tenet:statistic` -- whose fields are `stat` and `value`. The schemas do not catch it, because they
#: validate the *envelope* and cannot know what a codec wants. So these names come from the task classes
#: themselves (`quest/task/*.java`, each declaring a `TYPE` and a `FIELDS` set) rather than from the schema.
TASK_KINDS = ("item", "xp", "stat", "kill")


#: How far apart the nodes sit, on the 32-unit grid the format uses. **One default node wide**, which is as
#: tight as a pack can be without overlapping: a quest with no `size` is `QuestLayout.DEFAULT_SIZE` = 48, so
#: 48 units is nodes touching edge to edge. It was 96, and at a thousand nodes that made a block so wide that
#: any usable zoom showed a corner of it -- which measures the cull rather than the graph.
SPACING = 48


#: What each **task** type accepts, read from the classes that declare them.
#:
#: **The schemas cannot check this and that is the whole reason these tables are here.** They validate the
#: *envelope* — a `type` string and a bag of fields — and have no way to know that `tenet:xp` wants `value`
#: while `tenet:item` wants `count`. A generated pack that gets it wrong passes every schema and then loads
#: 98 files out of 1034, with `these fields do not form a tenet:xp` as the only clue.
#:
#: **Tasks and rewards are separate tables because the ids collide.** `tenet:xp` is a task *and* a reward,
#: in two registries, with two codecs — the task counts by `value` and the reward by `amount`. One table keyed
#: on the id could only be right for one of them, which is exactly the trap: a name that is correct on the
#: task side is a refusal on the reward side.
#:
#: Kept deliberately small: only the types this generator emits. A type that is not here is not checked,
#: which is honest — this is a guard on what the script writes, not a second copy of the mod's registry.
TASK_FIELDS = {
    "tenet:item": {"item", "count", "consumeItems", "match", "onlyFromCrafting"},
    "tenet:xp": {"value", "points"},
    "tenet:stat": {"stat", "value"},
    "tenet:kill": {"entity", "entityTypeTag", "customName", "nbtFilter", "count"},
}

#: What each **reward** type accepts. See `TASK_FIELDS` on why this is not one table.
REWARD_FIELDS = {
    "tenet:item": {"item", "count", "match", "components"},
    "tenet:xp": {"amount", "levels"},
}


def item_ref(item):
    """An `itemRef`: the object form the schema requires, not a bare string."""
    return {"item": item}


def task_for(kind, item, count):
    """
    One task of the given kind.

    ## Why only four kinds, and why they are these

    A stress pack is measured by what the canvas *draws*, and a task contributes a row to the card rather
    than a pixel to the canvas. So the variety that matters is that a card has rows of different shapes --
    an item row with an icon, a bare count, a named statistic -- and not that every task type in the engine
    appears. Those are the worked example's job.
    """
    if kind == "item":
        return {"type": "tenet:item", "item": item, "count": count, "consumeItems": False}
    if kind == "xp":
        # `value`, not `amount` -- and the codec clamps it to 1..100000.
        return {"type": "tenet:xp", "value": count * 10}
    if kind == "stat":
        # `tenet:stat`, not `tenet:statistic`, and its fields are `stat` and `value`.
        return {"type": "tenet:stat", "stat": "minecraft:mine_block", "value": count}
    return {"type": "tenet:kill", "entity": "minecraft:zombie", "count": count}


def reward_for(index, item):
    """A reward, alternating between the two kinds that need one field."""
    if index % 3 == 0:
        # `amount`, **not** the `value` the xp *task* takes: the two registries share the id `tenet:xp` and
        # have separate codecs, so a name that is right on the task side is a refusal here.
        return {"type": "tenet:xp", "amount": 25}
    return {"type": "tenet:item", "item": item}


def quest_id(chapter_index, quest_index):
    """A stable, sortable id. Sortable because a directory listing is then also the pack's order."""
    return f"stress_{chapter_index:02d}_{quest_index:03d}"


def quest_for(rng, chapter_index, quest_index, dependency_ids, mode, grid_columns):
    """
    One quest, with its connections and its styling drawn from the fixed random stream.

    ## The two things that are not random

    The **id** and the **position** are functions of the indices, so a quest is where it was last time and
    a baseline can be retaken. Everything that only affects what is *drawn* -- which node connects to
    which, how the line is styled, the outline, the icon -- comes from `rng`, which is seeded.
    """
    qid = quest_id(chapter_index, quest_index)
    item = ITEMS[(chapter_index * 7 + quest_index) % len(ITEMS)]

    quest = {
        "$schema": "../../_schema/quest.schema.json",
        "id": qid,
        "title": f"Stress {chapter_index:02d}-{quest_index:03d}",
        "icon": item_ref(item),
        # A **near-square, tightly packed** block. Near-square because with a thousand nodes the shape of the
        # block decides what the canvas looks like at a given zoom: a wide ribbon needs a wide canvas and puts
        # most of the pack off-screen at any usable scale, which measures the cull rather than the graph. Tight
        # because the point of the pack is the *graph* -- its fan-in, its cross-links, its routes -- and the
        # further apart the nodes sit the more of it is off-screen at once. See `SPACING`. It is a function of
        # the index, so the layout is identical on every run.
        "x": (quest_index % grid_columns) * SPACING,
        "y": (quest_index // grid_columns) * SPACING,
        "shape": SHAPES[(chapter_index + quest_index) % len(SHAPES)],
        "iconScale": round(0.25 + 0.75 * ((chapter_index * 3 + quest_index) % 5) / 4, 2),
    }

    if dependency_ids:
        quest["dependsOn"] = list(dependency_ids)
        # The mode is per quest rather than per chapter for most of the pack, so a single chapter contains
        # several bars at once -- which is what makes the canvas draw both satisfied and unsatisfied lines.
        quest["prerequisiteMode"] = mode
        if mode in ("one_completed", "one_started") and len(dependency_ids) > 1:
            # "Any two of these" -- the `minRequired` spelling, which is a third rule again.
            quest["minRequired"] = rng.randint(1, len(dependency_ids))

        # Per-line overrides, keyed by the dependency they come from. Only some lines get one, because a
        # chapter where every line is overridden never exercises the chapter default.
        if rng.random() < 0.35:
            style = {}
            if rng.random() < 0.6:
                style["form"] = rng.choice(FORMS)
            if rng.random() < 0.5:
                style["arrowHead"] = rng.choice(ARROW_HEADS)
            if rng.random() < 0.4:
                style["arrowPlace"] = rng.choice(ARROW_PLACES)
            if rng.random() < 0.3:
                style["arrowDensity"] = rng.choice(ARROW_DENSITIES)
            if rng.random() < 0.4:
                style["weight"] = rng.choice(WEIGHTS)
            if rng.random() < 0.4:
                style["dash"] = rng.choice(DASHES)
            if style.get("form") == "curved":
                style["bend"] = round(rng.uniform(-0.8, 0.8), 2)
            if style:
                quest["dependencyLines"] = {rng.choice(dependency_ids): style}

    # A card with rows of several kinds. Two to four, so the cards are not all the same height.
    count = rng.randint(2, 4)
    quest["tasks"] = [
        task_for(TASK_KINDS[(chapter_index + quest_index + i) % len(TASK_KINDS)], item, i + 1)
        for i in range(count)
    ]
    if (chapter_index + quest_index) % 4 != 3:
        quest["rewards"] = [reward_for(chapter_index + quest_index, item)]
    return quest


def chapter_for(rng, index, count):
    """
    One chapter: its quests, their dependencies, and its own default line style.

    ## The dependency graph

    A **randomised spanning tree** -- each quest after the first depends on an earlier one drawn at
    random -- plus **extra cross-links**, which is what gives the graph a realistic shape. A chain would be
    connected and useless: every node would have one in and one out, and the canvas would draw 80 straight
    lines. A tree plus cross-links has fan-in, fan-out and a few nodes carrying many edges, which is the
    case the route memo and the drag frame are actually about.

    Every dependency names an **earlier** quest in the same chapter, so the graph is acyclic by
    construction -- a cycle is a loader error, and a generator that can emit one is a generator that
    sometimes produces a pack which will not load.
    """
    chapter_id = f"stress_{index:02d}"
    # **Not chapter 0**, and that matters more than it looks: a `linear` chapter's order *is* its
    # progression and it declares no dependencies at all, so making the first chapter linear would throw
    # away the whole randomised graph -- the fan-in, the cross-links, the per-line overrides keyed by
    # dependency. The default pack is one chapter, so this is the difference between a stress graph and a
    # chain of 960 boxes. `--chapters 6` puts a linear one in the set, which is how that mode gets covered.
    linear = index in (5,)
    # A near-square block. `int(sqrt(count))` rather than a constant, so `--quests 4000` is still a
    # sensible shape instead of a ribbon a thousand nodes long.
    grid_columns = max(1, int(count ** 0.5))
    quests = []
    ids = []

    for q in range(count):
        dependencies = []
        if q > 0:
            # The tree edge: any earlier quest.
            dependencies.append(ids[rng.randrange(q)])
            # Cross-links: zero to two more, which is where fan-in comes from.
            for _ in range(rng.randint(0, 2)):
                candidate = ids[rng.randrange(q)]
                if candidate not in dependencies:
                    dependencies.append(candidate)
        mode = MODES[(index + q) % len(MODES)]
        quests.append(quest_for(rng, index, q, dependencies, mode, grid_columns))
        ids.append(quest_id(index, q))

    # The chapter's own default, so most lines take one path and the overrides are the exception. The two
    # linear chapters get no default style at all, so the built-in is exercised too.
    chapter = {
        "$schema": "../../_schema/chapter.schema.json",
        "id": chapter_id,
        "title": f"Stress Chapter {index + 1}",
        "subtitle": f"{count} quests, generated",
        "icon": item_ref(ITEMS[index % len(ITEMS)]),
        "quests": [quest["id"] + ".json" for quest in quests],
    }
    if linear:
        # The list order *is* the progression, and the declared dependencies are ignored -- which is the
        # other progression rule and a different set of lines.
        chapter["progressionMode"] = "linear"
    else:
        chapter["dependencyStyle"] = {
            "form": FORMS[index % len(FORMS)],
            "arrowHead": ARROW_HEADS[index % len(ARROW_HEADS)],
            "weight": WEIGHTS[index % len(WEIGHTS)],
            "dash": DASHES[index % len(DASHES)],
        }
    return chapter_id, chapter, quests


def write_pack(target, chapters, dry_run):
    """Writes the whole group, replacing any previous one of the same name."""
    group_dir = target / GROUP_ID
    created = 0

    if group_dir.exists() and not dry_run:
        # Wholesale, because every file in here is generated and none of it is the author's. A merge would
        # leave the previous run's quests behind and the pack would grow on every invocation.
        shutil.rmtree(group_dir)
    if not dry_run:
        group_dir.mkdir(parents=True, exist_ok=True)

    for chapter_id, chapter, quests in chapters:
        folder = group_dir / chapter_id
        if not dry_run:
            folder.mkdir(parents=True, exist_ok=True)
            (folder / "chapter.json").write_text(
                json.dumps(chapter, indent=2) + "\n", encoding="utf-8")
        created += 1
        for quest in quests:
            if not dry_run:
                (folder / (quest["id"] + ".json")).write_text(
                    json.dumps(quest, indent=2) + "\n", encoding="utf-8")
            created += 1

    # The group manifest. `chapters` lists every folder, because a folder that is present and unlisted
    # makes the loader refuse the whole group -- the same rule `seed_quests.py` merges for.
    group = {
        "$schema": "../_schema/group.schema.json",
        "id": GROUP_ID,
        "title": GROUP_TITLE,
        "description": [
            "Generated by `tenet/tools/stress_quests.py` for measurement, not for reading.",
            "",
            "Every node outline, every line form, arrow head, arrow place, density, weight and dash,",
            "several prerequisite modes and two linear chapters -- over a randomised dependency graph",
            "with cross-links. Delete the folder to be rid of it; regenerate it to get the same pack",
            "back.",
        ],
        "icon": item_ref("minecraft:clock"),
        "chapters": [chapter_id for chapter_id, _, _ in chapters],
    }
    if not dry_run:
        (group_dir / "group.json").write_text(
            json.dumps(group, indent=2) + "\n", encoding="utf-8")
    created += 1
    return created


def add_to_group(target, dry_run):
    """
    Declares this group in the target's `index.json`, **if the target has one**.

    ## Why the index is optional, and why that is not a bug

    The quest root may hold an `index.json` naming the top level of the book in reading order. When it is
    there the loader reads the root through it; when it is absent it walks the folders instead. The shipped
    example relies on that second path -- it has no `index.json` at all -- which is why a fresh seed has
    none.

    So this does nothing when the file is absent, and that is the correct behaviour rather than a warning:
    a group folder is discovered by the walk. It matters only when something *has* written an index, since
    an index is then the whole of the root and a folder it does not name is a folder nothing reads.

    The entry shape is `{"group": "<name>"}` and nothing else -- an entry names exactly one thing, and the
    chapters come from the group's own `group.json`.
    """
    index_path = target / "index.json"
    if not index_path.exists():
        return 0
    try:
        index = json.loads(index_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as problem:
        print(f"  ! index.json unreadable ({problem}) -- the group folder is written, and an index that"
              f" cannot be read is the loader's problem to report")
        return 0

    entries = index.get("entries")
    if not isinstance(entries, list):
        print("  ! index.json has no `entries` list -- leaving it alone; the loader will say so")
        return 0
    if any(isinstance(entry, dict) and entry.get("group") == GROUP_ID for entry in entries):
        return 0

    entries.append({"group": GROUP_ID})
    if not dry_run:
        index_path.write_text(json.dumps(index, indent=2) + "\n", encoding="utf-8")
    return 1


def verify_pack(target):
    """
    Checks a generated pack against the shipped schemas, and reports what is wrong.

    ## Why this exists rather than "look at the log"

    A generated pack that does not load is worse than no pack, because the failure looks like a mod fault
    rather than a generator fault -- and the whole point of this script is to make a measurement possible,
    not to add a thing to debug. The loader is the final authority and this cannot replace it, but it can
    answer *before* the game is opened, which is the difference between a five-second check and a
    round trip through a title screen.

    It checks the four things that a generator can get wrong and a schema can see: every file against its
    schema, every `dependsOn` resolving, every chapter listing exactly the files on disk, and the group
    listing every chapter folder. The last two are the loader's own refusal rules, and they are the ones a
    hand-written generator most often breaks.

    Returns the number of problems, so the caller can exit non-zero.
    """
    try:
        import jsonschema
    except ImportError:
        print("  ! `jsonschema` is not installed, so the schema check is skipped")
        print("      pip install jsonschema     (the other three checks still run)")
        jsonschema = None

    group_dir = target / GROUP_ID
    if not group_dir.is_dir():
        print(f"  ! no {GROUP_ID}/ in {target} -- nothing to verify")
        return 1

    schema_dir = target / "_schema"
    if not (schema_dir / "quest.schema.json").exists():
        # A profile has no `_schema`: the directory is a repo-side asset that ships with the examples, not
        # something a config folder carries. Falling back to the repo's copy is what lets this check run
        # where the pack actually is, which is the only place it is useful.
        schema_dir = pathlib.Path(__file__).resolve().parent / "quests" / "_schema"
    quest_schema = chapter_schema = None
    if jsonschema is not None:
        try:
            quest_schema = json.loads((schema_dir / "quest.schema.json").read_text(encoding="utf-8"))
            chapter_schema = json.loads((schema_dir / "chapter.schema.json").read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as problem:
            print(f"  ! cannot read the schemas in {schema_dir} ({problem}) -- schema check skipped")
            quest_schema = chapter_schema = None

    problems = []
    ids = {}
    quest_files = []
    chapters = sorted(entry for entry in group_dir.iterdir() if entry.is_dir())

    for chapter_dir in chapters:
        manifest_path = chapter_dir / "chapter.json"
        if not manifest_path.exists():
            problems.append(f"{chapter_dir.name}: no chapter.json")
            continue
        try:
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        except json.JSONDecodeError as problem:
            problems.append(f"{chapter_dir.name}/chapter.json: does not parse ({problem})")
            continue

        if chapter_schema is not None:
            for error in jsonschema.Draft202012Validator(chapter_schema).iter_errors(manifest):
                problems.append(f"{chapter_dir.name}/chapter.json: {error.message}")

        on_disk = sorted(f.name for f in chapter_dir.iterdir()
                         if f.is_file() and f.name.endswith(".json") and f.name != "chapter.json")
        listed = sorted(manifest.get("quests", []))
        for missing in sorted(set(on_disk) - set(listed)):
            problems.append(f"{chapter_dir.name}: {missing} is on disk and not in `quests` "
                            f"(the loader refuses the chapter)")
        for absent in sorted(set(listed) - set(on_disk)):
            problems.append(f"{chapter_dir.name}: {absent} is listed and not on disk")

        for name in on_disk:
            path = chapter_dir / name
            try:
                quest = json.loads(path.read_text(encoding="utf-8"))
            except json.JSONDecodeError as problem:
                problems.append(f"{chapter_dir.name}/{name}: does not parse ({problem})")
                continue
            if quest_schema is not None:
                for error in jsonschema.Draft202012Validator(quest_schema).iter_errors(quest):
                    problems.append(f"{chapter_dir.name}/{name}: {error.message}")
            qid = quest.get("id")
            if qid in ids:
                problems.append(f"id {qid!r} is declared twice: {ids[qid]} and "
                                f"{chapter_dir.name}/{name} (progress would land on the wrong one)")
            else:
                ids[qid] = f"{chapter_dir.name}/{name}"
            # **The check the schema cannot make.** A `type` the mod does not register, or a field its codec
            # does not declare, is refused by the loader as "these fields do not form a tenet:xp" -- which
            # says neither which field is wrong nor what it should be. Tasks and rewards go through their own
            # tables, because the same id means different fields in each registry.
            for kind, fields_of in (("tasks", TASK_FIELDS), ("rewards", REWARD_FIELDS)):
                for entry in quest.get(kind, []):
                    declared = entry.get("type")
                    if declared not in fields_of:
                        problems.append(f"{chapter_dir.name}/{name}: {kind} type {declared!r} is not one "
                                        f"this generator knows the fields of")
                        continue
                    allowed = fields_of[declared]
                    for field in entry:
                        if field != "type" and field not in allowed:
                            problems.append(f"{chapter_dir.name}/{name}: {kind} {declared} has no field "
                                            f"{field!r} (it declares {sorted(allowed)})")
            quest_files.append(quest)

    # Every reference resolving, which no single file can answer.
    for quest in quest_files:
        for dependency in quest.get("dependsOn", []):
            if dependency not in ids:
                problems.append(f"{quest.get('id')}: dependsOn {dependency!r}, which no quest declares")

    group_manifest = group_dir / "group.json"
    if not group_manifest.exists():
        problems.append("stress/group.json is missing")
    else:
        try:
            group = json.loads(group_manifest.read_text(encoding="utf-8"))
            listed_chapters = sorted(group.get("chapters", []))
            for absent in sorted(set(listed_chapters) - {c.name for c in chapters}):
                problems.append(f"group.json lists chapter {absent!r}, which is not a folder")
            for missing in sorted({c.name for c in chapters} - set(listed_chapters)):
                problems.append(f"group.json does not list chapter {missing!r} "
                                f"(the loader refuses the whole group)")
        except json.JSONDecodeError as problem:
            problems.append(f"group.json does not parse ({problem})")

    print(f"  {len(quest_files)} quest(s) in {len(chapters)} chapter(s), {len(ids)} distinct id(s)")
    if problems:
        print(f"  {len(problems)} problem(s):")
        for problem in problems[:40]:
            print(f"    - {problem}")
        if len(problems) > 40:
            print(f"    ... and {len(problems) - 40} more")
        return len(problems)
    print("  OK: every file matches its schema, every id is unique, every reference resolves,")
    print("      every chapter lists exactly its files, and the group lists every chapter")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="Generate a large synthetic pack for measurement.")
    parser.add_argument("targets", nargs="*", metavar="DIR",
                        help="config/tenet/quests directories to write into")
    parser.add_argument("--workspace", action="store_true",
                        help="write into this repo's test combos and both Modrinth profiles")
    parser.add_argument("--quests", type=int, default=CHAPTERS * PER_CHAPTER,
                        help=f"total quests (default {CHAPTERS * PER_CHAPTER})")
    parser.add_argument("--chapters", type=int, default=CHAPTERS,
                        help=f"how many chapters to spread them over (default {CHAPTERS}; more than one is"
                             f" a different pack, because a canvas draws a chapter)")
    parser.add_argument("--dry-run", action="store_true", help="report without writing")
    parser.add_argument("--verify", action="store_true",
                        help="check an existing pack against the schemas instead of writing one")
    args = parser.parse_args()

    targets = [pathlib.Path(t) for t in args.targets]
    if args.workspace:
        root = pathlib.Path(__file__).resolve().parents[2]
        targets += [root / "testserver/combos/tenet/fabric/mods/../config/tenet/quests",
                    root / "testserver/combos/tenet/neoforge/mods/../config/tenet/quests"]
        profiles = pathlib.Path.home() / "AppData" / "Roaming" / "ModrinthApp" / "profiles"
        for name in ("Mod Testing Fabric 1.21.1", "Mod Testing NeoForge 1.21.1"):
            profile = profiles / name
            if profile.is_dir():
                targets.append(profile / "config" / "tenet" / "quests")
    if not targets:
        parser.error("name at least one directory, or pass --workspace")

    # The chapter count is derived from the requested total so `--quests 2000` does not silently keep
    # making twelve chapters of eighty. One chapter by default: see the note on CHAPTERS.
    per_chapter = max(1, args.quests // max(1, args.chapters))
    chapter_count = max(1, args.chapters)
    failed = 0
    print(f"{per_chapter * chapter_count} quest(s) over {chapter_count} chapter(s) of {per_chapter}, "
          f"seed {SEED:#x}")

    for target in targets:
        if not target.is_dir():
            print(f"\n{target}\n  (not a directory - skipped; seed the examples first)")
            continue
        print(f"\n{target}")
        if args.verify:
            failed += verify_pack(target)
            continue
        rng = random.Random(SEED)
        chapters = [chapter_for(rng, i, per_chapter) for i in range(chapter_count)]
        created = write_pack(target, chapters, args.dry_run)
        indexed = add_to_group(target, args.dry_run)
        verb = "would write" if args.dry_run else "wrote"
        print(f"  {verb} {created} file(s) in {GROUP_ID}/"
              + (", and declared the group in index.json" if indexed
                 else ", and no index.json here so the folder is found by the walk"))

    if args.verify:
        return 1 if failed else 0
    print("\nDelete the `stress` folder (and its index.json entry) to be rid of it.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
