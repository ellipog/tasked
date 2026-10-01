"""
seed_quests.py - put the example questlines into a config directory.

Why this is a script and not something the mod does
---------------------------------------------------
Tasked ships no quests. It is a quest *engine*, and a mod that installs example chapters into
every player's config directory has decided something that is not its to decide: the first thing a
pack author would have to do is delete somebody else's content, and every file a mod ships is a file
that has to keep working forever against a format that is still moving.

So the worked examples live here, beside this script, and they reach a config directory when somebody
asks for them:

    python tasked/tools/seed_quests.py <dir> [<dir>...]     any config/tasked/quests directories
    python tasked/tools/seed_quests.py --workspace          this repo's own test setup

A `<dir>` is a `config/tasked/quests` directory - the one the mod reads. It is created if it is not
there.

Nothing is overwritten unless you say so
----------------------------------------
An existing file is left exactly as it is, and that is the point rather than a nicety. This directory
is where an author's own questline lives, so a tool that refreshed it on every run would destroy work
and be indistinguishable from a bug -- which is the same failure the mod used to have, arriving from
the other direction.

So there are two ways to get a pristine copy, and neither is silent: delete the file and run this
again, or pass `--force`. `--force` names each file it replaces, so a replace is never something that
happened while you were not looking.

`--workspace` seeds every test combo and both Modrinth profiles, and is the mode this project uses.
It is also why this script is here rather than in `.utils/`: it belongs to the Tasked repository, so
it travels with the examples it copies, and a separate checkout of Tasked gets both.

Files beginning with `_` are **not** copied, at any level. Those are deliberately-broken fixtures -
the loader skips them by that prefix - and copying one would install a questline whose only purpose is
to fail. The same prefix is why `_schema/` is here at all: it is a folder of editor schemas beside the
content, and the rule is what keeps the loader from error-walling on a folder the mod ships itself.

It also **removes the version-1 files it has replaced.** Converting an install means the same
questline exists twice -- once as `01_stone_age.json`, once as `getting_started/` -- and leaving both
is not a stale copy, it is two questlines: every group, chapter and quest in the pair is reported as a
duplicate id. So a root-level `.json` whose declared chapter groups are all provided by folders that
were just copied is deleted, and anything else at that level is **kept and named**, with the reason.
See `sweep_legacy_flat_files` for the rule and why it is stated in ids rather than in file names.
"""

import argparse
import json
import pathlib
import shutil
import sys

# This script lives in tasked/tools/, so the repository is one level up and the workspace two.
TOOLS = pathlib.Path(__file__).resolve().parent
QUESTS = TOOLS / "quests"
WORKSPACE = TOOLS.parent.parent

# The two manifest names the loader fixes, so the sweep below can tell a group manifest from a quest
# file without asking the mod. Must match `QuestFiles.GROUP_MANIFEST` and `CHAPTER_MANIFEST`.
GROUP_MANIFEST = "group.json"
CHAPTER_MANIFEST = "chapter.json"

# The workspace's own test setup. Both are absent on a fresh clone, and neither is an error.
COMBOS = WORKSPACE / "testserver" / "combos"
LOADERS = ("fabric", "neoforge")

# The launcher's profiles, where the mod actually runs for a person. Resolved from the home
# directory rather than written down, so nothing here is a path to this machine.
LAUNCH_PROFILES = pathlib.Path.home() / "AppData" / "Roaming" / "ModrinthApp" / "profiles"
PROFILE_NAMES = ("Tasked Fabric", "Tasked NeoForge")

# Relative to a config directory. Must match QuestLoader.DIRECTORY in the mod.
QUEST_DIRECTORY = pathlib.Path("config") / "tasked" / "quests"


def example_files():
    """
    Every example file to copy, in a stable order, as paths relative to `quests/`.

    Recursive, and it had to become recursive when the format changed. It was `QUESTS.glob("*.json")`
    — right for the flat format, where a file *is* a whole tree, and silently empty under the folder
    format, where every quest is four levels down inside `getting_started/first_steps/`. A glob that
    matches nothing is not an error: this would have copied no files at all, printed a cheerful
    summary of zero, and left the caller with an empty quest book and nothing in any log to explain
    it.

    **Relative paths, not names**, because a name is no longer unique: `group.json` appears once per
    group and `chapter.json` once per chapter. Returning bare names would make the copy step
    overwrite eleven group manifests with twelve others, in whatever order `sorted` produced.

    The `_` rule applies to **every** segment, not just the last. `_schema/` is a directory, so a rule
    that tested only the file's own name would copy all three schema files into the config directory —
    where the loader's own walk would skip them, making the mistake invisible from here and visible
    only as three stray files in somebody's install.
    """
    if not QUESTS.is_dir():
        return []
    out = []
    for path in sorted(QUESTS.rglob("*")):
        if not path.is_file():
            continue
        relative = path.relative_to(QUESTS)
        if any(segment.startswith("_") for segment in relative.parts):
            continue
        out.append(relative)
    return out


def workspace_targets():
    """
    Every config directory this workspace cares about.

    The combos are seeded because the test servers read them, and the profiles because that is where
    the mod is actually played. Profiles are only used when the profile directory exists: creating
    `config/tasked/quests` inside a profile you have is a kindness, and conjuring a profile folder
    the launcher does not know about is not.

    `_tools` and `_loaders` are skipped by name. They sit in the same folder as the combos and are
    infrastructure rather than combos, and `testserver/README.md` documents that convention.
    """
    targets = []

    if COMBOS.is_dir():
        for combo in sorted(p for p in COMBOS.iterdir() if p.is_dir()):
            if combo.name.startswith("_"):
                continue
            # Both the combo's own config and each loader's copy of it. `setservers.ps1` seeds the
            # former into the latter, and the loaders read their own, so both have to be current.
            targets.append(combo / QUEST_DIRECTORY)
            for loader in LOADERS:
                targets.append(combo / loader / QUEST_DIRECTORY)

    for name in PROFILE_NAMES:
        profile = LAUNCH_PROFILES / name
        if profile.is_dir():
            targets.append(profile / QUEST_DIRECTORY)

    return targets


def display(path: pathlib.Path) -> str:
    """A path as a person should read it: relative to the workspace when it is inside it."""
    try:
        return str(path.relative_to(WORKSPACE))
    except ValueError:
        return str(path)


def seed(target: pathlib.Path, files, force: bool, dry_run: bool):
    """
    Copy the examples into one target, then clear away the flat files they replaced.

    Returns `(created, replaced, kept, removed, survivors)`. `survivors` is the list of root-level
    JSON files that were left alone — see `sweep_legacy_flat_files`, which is where the interesting
    decision lives.
    """
    created = replaced = kept = 0
    print(f"\n{display(target)}")

    if not dry_run:
        target.mkdir(parents=True, exist_ok=True)

    for relative in files:
        source = QUESTS / relative
        # The relative path is preserved rather than flattened. Under the folder format the path *is*
        # information: `getting_started/first_steps/punch_a_tree.json` is a quest, and a bare
        # `punch_a_tree.json` written to the quest root is a version-1 file the loader would try to read
        # as a whole tree — and report a typo about, at a line that does not exist.
        destination = target / relative
        existed = destination.exists()

        if existed and not force:
            print(f"  = {relative.as_posix()}  kept, already there")
            kept += 1
            continue

        if not dry_run:
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, destination)

        verb = "~" if existed else "+"
        note = "  [replaced]" if existed else ""
        print(f"  {verb} {relative.as_posix()}  ({source.stat().st_size} bytes){note}")

        if existed:
            replaced += 1
        else:
            created += 1

    removed, survivors = sweep_legacy_flat_files(target, files, dry_run)
    return created, replaced, kept, removed, survivors


def sweep_legacy_flat_files(target: pathlib.Path, files, dry_run: bool):
    """
    Delete the version-1 flat files that the folder tree beside them has replaced.

    ## Why this is a deletion, and why it is not `--force`'s job

    Converting an install means the same questline exists twice: once as `01_stone_age.json`, and once
    as `getting_started/`. Leaving both is worse than a stale copy — it is *two questlines*, and every
    group, chapter and quest in the pair is reported as a duplicate id. The message an author gets
    names 01_stone_age.json and a file inside the folder as the two sides of a clash, which reads as a
    broken conversion rather than as a file that needs deleting.

    So the script tidies up after itself, and it is the only thing in this repository that deletes a
    quest file. That is why the rule it deletes by is narrow, and why every case it *declines* is
    named out loud rather than passed over.

    ## The rule

    A `*.json` directly at the target's quest root is deleted **only if** every chapter-group id it
    declares is also provided by a group folder that was just copied. So:

      * `01_stone_age.json`, declaring `getting_started`, goes — because `getting_started/group.json`
        is now there. That is exactly the stale copy.
      * An author's own `my_questline.json`, declaring a group of its own, **stays**, and is named.
        Nothing copied declares that group, so it is not a copy of anything this script wrote, and it
        is not this script's to remove.

    Matching on ids rather than on file names is the point. A name list would be a thing that goes
    stale — the four names live in this file's history and nowhere else now — and it would delete
    `01_stone_age.json` even if an author had rewritten it into something of their own.

    ## What it will not do

    No directory is deleted. An empty group folder is a legitimate placeholder, and a script that
    removed folders would eventually remove one an author had just made.

    `_`-prefixed files are left alone entirely: that prefix is how a file is deliberately kept out of
    the loader's way, so removing one would defeat the convention.

    A file that will not parse is left alone and named. It is not a v1 file this can recognise, and
    deleting a file this cannot read is the one thing a tidy-up must never do.
    """
    # Which group ids the folder tree now provides, read from the manifests that were just copied.
    #
    # Read from the *copy list* rather than from the target directory, so `--dry-run` reports exactly
    # the same deletions a real run would perform while touching nothing. A dry run that predicted
    # differently from the run it was predicting would be worse than no dry run at all.
    provided = set()
    for relative in files:
        if relative.name != GROUP_MANIFEST:
            continue
        try:
            declared = json.loads((QUESTS / relative).read_text(encoding="utf-8")).get("id")
        except (OSError, json.JSONDecodeError):
            continue
        if isinstance(declared, str):
            provided.add(declared)

    if not target.is_dir():
        return 0, []

    removed = 0
    survivors = []
    for candidate in sorted(target.glob("*.json")):
        if candidate.name.startswith("_"):
            continue
        try:
            data = json.loads(candidate.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as problem:
            survivors.append((candidate.name, f"it will not parse ({problem})"))
            continue

        if not isinstance(data, dict) or "chapterGroups" not in data:
            survivors.append((candidate.name, "it is not a version-1 file"))
            continue

        groups = data.get("chapterGroups") or []
        ids = [g.get("id") for g in groups if isinstance(g, dict)]
        if not ids:
            survivors.append((candidate.name, "it declares no chapter groups"))
            continue

        missing = [group_id for group_id in ids if group_id not in provided]
        if missing:
            survivors.append((candidate.name,
                              "it declares " + ", ".join(repr(m) for m in missing)
                              + ", which no copied group folder provides"))
            continue

        if not dry_run:
            candidate.unlink()
        print(f"  - {candidate.name}  removed: a version-1 file whose group(s) "
              + ", ".join(repr(g) for g in ids) + " are now in folders beside it")
        removed += 1

    for name, reason in survivors:
        print(f"  ! {name}  KEPT -- {reason}. A version-1 file beside the folders would load as a"
              "\n      second copy of whatever it declares, so every shared id is reported as a"
              "\n      duplicate. Delete it, or move it somewhere the loader does not read.")

    return removed, survivors


def main() -> int:
    parser = argparse.ArgumentParser(
        prog="seed_quests.py",
        description="Copy the example questlines into config/tasked/quests directories. "
                    "Existing files are left alone unless --force is given.")
    parser.add_argument("targets", nargs="*", metavar="DIR",
                        help="a config/tasked/quests directory, created if it is not there")
    parser.add_argument("--workspace", action="store_true",
                        help="seed this repo's test combos and both Modrinth profiles")
    parser.add_argument("--force", action="store_true",
                        help="overwrite files that are already there, naming each one")
    parser.add_argument("--dry-run", action="store_true",
                        help="say what would happen and change nothing")
    args = parser.parse_args()

    files = example_files()
    if not files:
        print(f"no example quests at {display(QUESTS)}")
        return 1

    targets = [pathlib.Path(t) for t in args.targets]
    if args.workspace:
        found = workspace_targets()
        if not found:
            print("--workspace found nothing to seed: no testserver/combos, and neither launch "
                  "profile exists")
            return 1
        targets.extend(found)

    if not targets:
        parser.error("give at least one directory, or --workspace")

    print(f"examples in {display(QUESTS)}")
    for relative in files:
        print(f"  {relative.as_posix()}  ({(QUESTS / relative).stat().st_size} bytes)")

    created = replaced = kept = removed = 0
    survivors = []
    for target in targets:
        a, b, c, d, s = seed(target, files, args.force, args.dry_run)
        created += a
        replaced += b
        kept += c
        removed += d
        survivors.extend(s)

    print()
    if args.dry_run:
        print("dry run: nothing was written")
    print(f"created {created}, replaced {replaced}, kept {kept}, removed {removed} file(s) across "
          f"{len(targets)} director{'y' if len(targets) == 1 else 'ies'}")

    if survivors:
        print(f"{len(survivors)} file(s) were kept and named above. Each is a version-1 file sitting"
              " beside the folders that replaced it, which loads as a second copy of whatever it"
              " declares -- so every shared id is reported as a duplicate. Nothing here deletes them,"
              " because a file this script cannot match to a folder it wrote might be yours.")

    if kept and not args.force:
        print("Existing files were left alone. That is the default: delete one and run this again "
              "for a fresh copy, or pass --force.")
    print("A running server reads these on /tasked reload, or on its next start.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
