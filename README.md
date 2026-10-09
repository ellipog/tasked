# Tenet

The modern questing engine for Fabric and NeoForge.

A pack author describes a quest line in JSON, Tenet draws it in a quest book, and the
server decides what counts as done. Tenet ships no quests of its own — it is an engine,
not content.

> **Before its first stable release.** The format and the API may still break, so read
> the release notes before upgrading.

## Why Tenet

- **The server decides what counts.** The client draws what it is told and nothing more, so editing your own game completes nothing.
- **[Validator points at file and line](docs/authoring/validation.md).** A bad file is reported with its path and skipped — one broken quest never takes the server down.
- **[Fifteen task, ten reward, six condition types](docs/authoring/tasks.md).** Tasks ask, rewards pay, conditions gate either — see the [task](docs/authoring/tasks.md), [reward](docs/authoring/rewards.md), and [condition](docs/authoring/conditions.md) guides.
- **[Editor with undo, and nothing really deletes](docs/index.md#removing-something-and-getting-it-back).** Bulk-select nodes, edit as one batch per chapter with one undo, and every delete is a `.deleted` tombstone that `/tenet restore` brings back.
- **[Parties share progress](docs/commands.md#parties).** Built on Armature teams — `ONE_MEMBER`, `POOLED`, or `OWNER_ONLY` counting, server-authoritative throughout.

## Playing

Install **Tenet and Armature** together, on client and server. The loader will name
Armature if it is missing. See the Modrinth page for the supported Minecraft version
and which Armature release to pair with this one.

Open the book with the Quest Book item or the `B` key. Details live in the manual:

- [The quest book and HUD](docs/index.md#the-book-briefly) — canvas, claims, parties
- [Commands](docs/commands.md) — every `/tenet` command
- [HUD](docs/hud.md) — pinning quests and notices

## Making quests

Quests are JSON under `config/tenet/quests/` — one folder per group, one file per
quest. Run `/tenet reload`, then press `B`.

Start here:

- [Manual front door](docs/index.md)
- [Folder format](docs/authoring/quest-files.md)
- [Quest fields](docs/authoring/quests.md)
- [Tasks](docs/authoring/tasks.md) · [Rewards](docs/authoring/rewards.md) · [Conditions](docs/authoring/conditions.md)
- [Validation](docs/authoring/validation.md) · [Languages](docs/authoring/languages.md) · [KubeJS](docs/authoring/kubejs.md) · [FTB mapping](docs/authoring/ftb-mapping.md)

The worked example in `tools/quests/` (see `tools/README.md`) carries every field the
format has, and the schemas under `tools/quests/_schema/` are the machine-readable
reference.

For addon mods: `TenetEvents` plus the task, reward, and condition registries. See the
releases page for the current Maven coordinate — it follows the shape
`dev.ellipog:tenet-common-<minecraft-line>:<release>` on `https://maven.ellipog.dev`.

## Building

```cmd
gradlew build
```

Jars land in `fabric/build/libs` and `neoforge/build/libs`. The pin Tenet compiles
against is `armature_version` in `gradle.properties` — that Armature release has to
exist first. For an unreleased Armature, publish it locally first (`publishToMavenLocal`
in the Armature checkout; `mavenLocal` wins), then build here.

## Licence

MIT — see [LICENSE](LICENSE).
