# Tasked documentation

Tasked is a questing mod: you describe quests in JSON, it draws them on a pannable canvas, and the
server decides what counts as done.

> [!NOTE]
> Tasked is at 0.1.0: the quest format, the loader and validator, the progression engine, the sync, the
> quest book and its in-game editor all work on both loaders. These pages describe what exists, and are
> written as the features land rather than in advance.

## Where to start

| Page | What it is |
|---|---|
| [Design preview](design-preview) | Every element these docs can render, on one page |
| [KubeJS scripting](kubejs) | Driving the questline from a script: stages, events, custom types |

## Descriptions are markdown

A description is an array of paragraphs, and each paragraph is read as **markdown** when a player opens the
quest:

- `**bold**`, `*italic*` (or `_italic_`) and `` `code` ``;
- `[text](https://example.com)` for a link — underlined, and it opens in the browser when clicked. Only
  `http` and `https` open; anything else is refused with a word in the status bar;
- a line starting with `#`..`######` is a heading, and one starting with `- `, `* ` or `+ ` is a bullet.
  Headings come in four sizes -- `#` is twice the body text, then `##` at 1.75x, `###` at 1.5x and
  `####` at 1.25x; `#####` and `######` use the smallest of those, because the card's font has one
  size and the body's is its floor;
- a backslash escapes the character after it (`\*` is a literal star).

A line break is a line break, exactly as it has always been: markdown here does not join lines into flowing
paragraphs, because these files are written a line per paragraph and joining them would re-wrap prose an
author had already wrapped. The file keeps the raw markdown — the editor shows it as written, and only the
reader's card renders it.

## What is coming

The authoring guide — how to write a quest file, what every field does, and how the validator reports
a mistake — is not written yet. Until it lands, the JSON schema in `docs/` is the reference, and the
example quests in `tools/quests/` are the worked examples.
