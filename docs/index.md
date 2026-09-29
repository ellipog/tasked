# Tasked documentation

Tasked is a questing mod: you describe quests in JSON, it draws them on a pannable canvas, and the
server decides what counts as done.

> [!NOTE]
> **There is no questing code yet.** Tasked is at 0.1.0 — it builds on both loaders, loads into the
> game, and logs a few lines on startup. These pages describe what exists, and are written as the
> features land rather than in advance.

## Where to start

| Page | What it is |
|---|---|
| [Design preview](design-preview) | Every element these docs can render, on one page |
| [The plan](https://github.com/ellipog/tasked/blob/main/plan.md) | All twelve stages, in the repository |

## What is coming

The authoring guide — how to write a quest file, what every field does, and how the validator reports
a mistake — lands with stage 10 of the plan. Until then the JSON schema in `docs/` is the reference,
and the example quests in `tools/quests/` are the worked examples.
