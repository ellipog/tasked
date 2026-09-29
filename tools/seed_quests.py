"""
seed_quests.py - put the example questlines into a config directory.

Why this is a script and not something the mod does
---------------------------------------------------
Tasked ships no quests. It is a quest *engine*, and a mod that installs three example chapters into
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

Files beginning with `_` are **not** copied. Those are deliberately-broken fixtures - the loader skips
them by that prefix - and copying one would install a questline whose only purpose is to fail.
"""

import argparse
import pathlib
import shutil
import sys

# This script lives in tasked/tools/, so the repository is one level up and the workspace two.
TOOLS = pathlib.Path(__file__).resolve().parent
QUESTS = TOOLS / "quests"
WORKSPACE = TOOLS.parent.parent

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
    """The examples to copy, in load order. Name-sorted, `_`-prefixed excluded."""
    if not QUESTS.is_dir():
        return []
    return sorted(p for p in QUESTS.glob("*.json") if not p.name.startswith("_"))


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
    """Copy the examples into one target. Returns (created, replaced, kept)."""
    created = replaced = kept = 0
    print(f"\n{display(target)}")

    if not dry_run:
        target.mkdir(parents=True, exist_ok=True)

    for source in files:
        destination = target / source.name
        existed = destination.exists()

        if existed and not force:
            print(f"  = {source.name}  kept, already there")
            kept += 1
            continue

        if not dry_run:
            shutil.copy2(source, destination)

        verb = "~" if existed else "+"
        note = "  [replaced]" if existed else ""
        print(f"  {verb} {source.name}  ({source.stat().st_size} bytes){note}")

        if existed:
            replaced += 1
        else:
            created += 1

    return created, replaced, kept


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
    for source in files:
        print(f"  {source.name}  ({source.stat().st_size} bytes)")

    created = replaced = kept = 0
    for target in targets:
        a, b, c = seed(target, files, args.force, args.dry_run)
        created += a
        replaced += b
        kept += c

    print()
    if args.dry_run:
        print("dry run: nothing was written")
    print(f"created {created}, replaced {replaced}, kept {kept} file(s) across "
          f"{len(targets)} director{'y' if len(targets) == 1 else 'ies'}")

    if kept and not args.force:
        print("Existing files were left alone. That is the default: delete one and run this again "
              "for a fresh copy, or pass --force.")
    print("A running server reads these on /tasked reload, or on its next start.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
