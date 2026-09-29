# Tasked

A modern questing mod for **Fabric** and **NeoForge**, for **Minecraft 1.21.1**. Built as a
ground-up replacement for FTB Quests — not a port of it, and not a reimplementation.

> **Status: 0.1.0 — skeleton.** It builds on both loaders, loads into the game, and logs a
> few lines on startup. There is no questing code yet. The full plan, all twelve stages of
> it, is in `plan.md`.

## Requirements

| | |
|---|---|
| Minecraft | 1.21.1 |
| Java | 21 |
| Fabric | Fabric Loader 0.16.9+, with Fabric API 0.109.0+1.21.1 |
| NeoForge | 21.1.80+ |
| Armature | Not yet — see below |

Tasked is built on **Armature**, a standalone library mod. The dependency is not wired up
yet: at 0.1.0 neither mod references the other, which keeps each one testable on its own.
It gets wired in as soon as Tasked actually consumes Armature, which is the first thing
stage 1 does.

## Building

```cmd
gradlew build
```

Jars land in `fabric/build/libs` and `neoforge/build/libs`. Install the plain jar
(`tasked-fabric-1.21.1-0.1.0.jar`) — the `-sources` and `-javadoc` jars are not mods.

## Deploying to a local test profile

Three tasks copy the freshly built jars straight into the Modrinth App profiles named in
`gradle.properties`:

```cmd
gradlew deployFabric     :: Fabric profile only
gradlew deployNeoForge   :: NeoForge profile only
gradlew deployAll        :: both
```

Each one deletes the previous copies from the profile before copying, so you never end up
with `tasked-...jar` and `tasked-... (1).jar` sitting side by side — Minecraft picks
whichever it likes, and the resulting bug hunt is never worth it.

If your profiles are named something else, edit these two lines in `gradle.properties`:

```properties
testModsDirFabric=C:/Users/Ellio/AppData/Roaming/ModrinthApp/profiles/Tasked Fabric/mods
testModsDirNeoForge=C:/Users/Ellio/AppData/Roaming/ModrinthApp/profiles/Tasked NeoForge/mods
```

## Layout

| Project | What goes there |
|---|---|
| `common/` | Compiled against vanilla only — the bulk of the code. Cannot see either loader. |
| `fabric/` | Fabric entry point and anything Fabric-specific. |
| `neoforge/` | NeoForge entry point and anything NeoForge-specific. |

`common/` cannot reference `fabric/` or `neoforge/`. That direction is enforced by the
build, not by convention.

Based on the [MultiLoader Template](https://github.com/Jaredlll08/MultiLoader-Template),
with the Forge subproject removed.

## Licence

MIT — see [LICENSE](LICENSE).
