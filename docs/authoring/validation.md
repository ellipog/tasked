# Validation

A problem in a quest file is cheap to make and expensive to find — a typo'd field name, a dependency
that points at a quest that was renamed, a cycle nobody can unlock. Tasked's answer is to read the
files before anything tries to trust them, and to say what is wrong in the format a compiler uses:

```
quests/first_light/first_steps/punch_a_tree.json:14:9: error: unknown field "titl" - did you mean "title"?
    valid fields here: description, icon, id, title
```

File, line, column, severity, message — and, for a field nobody declares, the suggestions on the same line
plus a second line naming the fields that would have worked. That is `DataProblem.render()` and the
message beside it, and it is what the log carries for every problem a reload finds.

## Two passes, because they catch different mistakes

**The per-file [[validator]] runs before the codecs do.** It checks structure and values: every
required field present, every field some type or the common set declares, every enum name valid, every
id well-formed, every item that exists. Codecs alone cannot do this job — they ignore a field they do not
recognise, so `"titl": "Punch a Tree"` produces a quest with a blank title, no error, and nothing in
any log. And a codec failure names the file but not the line, which is most of what an author needs.

Because a file with a structural error is **not decoded at all**, one mistake produces one message
rather than a validator complaint followed by a codec complaint about the same thing. The same holds
one level down: a broken entry in a task's or reward's `conditions` list is reported at the entry's
own line, and the enclosing task's codec does not repeat it — a condition whose `type` no build knows
is one error listing the condition types, not two messages saying it twice.

**Cross-file checks happen when the whole tree is known.** A file on its own cannot know that an id is
duplicated in another file, or that a `dependsOn` names a quest nobody wrote, or that two quests depend
on each other in a circle. Those are checked against the assembled tree, after every file has been
read.

**A chapter's own gate is checked in both passes, for the same split.** The per-file validator checks
the five fields' shape — a well-formed id in `dependsOn` and `completesWhen`, a known
`prerequisiteMode`, a `minRequired` inside its bounds, a boolean for `hideUntilDependenciesComplete` and
for the two `defaultHideUntilDependencies*` fields beside it — as well as the two reveal flags on a
quest, which are booleans when present and absent when the chapter decides. The cross-file pass is what
can say the thing that matters, because a chapter reference, a completes-quest and the graph they form
are only visible once every file is read:

- a `dependsOn` naming a chapter that does not exist — reported with a "did you mean", like a quest's;
- a chapter waiting on itself, and `minRequired` above the size of its own list;
- a `completesWhen` naming a quest that does not exist, since such a chapter never reports completed;
- **a dependency that asks for *completed* while the chapter it points at declares no
  `completesWhen`** — counted rather than reported per edge, because `one_completed` over three
  chapters needs one of them to be completable and `minRequired: 2` needs two. Each of these is an
  error rather than a warning: every one of them produces a chapter that can never be opened, and a
  chapter that never opens is the quietest failure a questline has;
- a **cycle** in the chapter graph, printed as a chain like a quest cycle. That includes the one no
  single file shows: chapter A waits on chapter B while B is finished by a quest inside A.

> [!TIP]
> `/tasked types` prints every task, reward and [[tasked:authoring/conditions|condition]] type this
> build has, and the field names each one takes — the fastest way to check a field name without
> opening a schema, and the list the validator itself uses.

## What happens when a file is wrong

The file is **skipped, not fatal**, and the scope of the skip is that file's own contents. A quest file
with an error costs that quest: the chapter it sits in still opens with the rest of its list. The reload
reports the tally:

```cmd
/tasked reload
```

Two things cost more than one file, and both are said in the log rather than left to be discovered:

- **A name with nothing behind it.** A `chapters` or `quests` entry that resolves to no folder or file
  is an error against *that name*, not against the manifest that listed it — so a quest file deleted by
  hand, or a chapter folder renamed on one side only, costs exactly the one thing that is missing. It
  used to be reported against the manifest, and a manifest carrying an error is refused, which took the
  chapter — or the whole group — with it.
- **A manifest that cannot say what it holds.** A `group.json` or `chapter.json` that will not parse,
  names no `id`, or has a `chapters`/`quests` that is not a list of names cannot be read at all, so its
  subtree does not load and the error is against that file. That is the only case where one file costs
  more than itself.

A root `index.json` is a third, and it is the one that is read *around* rather than skipped: one that
cannot declare the root is read as if it were not there, and the tree loads by folder name — see
[[tasked:authoring/quest-files]].

> [!WARNING]
> A file can decode cleanly and still be wrong — a circular dependency is the usual one. Reload counts
> files that had *problems*, not files that parsed, so "loaded" never means "correct".

The in-game editor refuses a write that would not validate, and reports the first problem's line, so
the round trip through the book cannot produce a file the loader would reject.

## Finding the problem when the message is not enough

`/tasked quest <id>` prints one quest as the engine sees it — where it is, what it depends on, which
flags are set — which is what tells you whether a file loaded as written or was overridden by an
inherited default:

```cmd
/tasked quest punch_a_tree
```

Two things cost more than one file, and both are said in the log rather than left to be discovered:

- **A name with nothing behind it.** A `chapters` or `quests` entry that resolves to no folder or file
  is an error against *that name*, not against the manifest that listed it — so a quest file deleted by
  hand, or a chapter folder renamed on one side only, costs exactly the one thing that is missing. It
  used to be reported against the manifest, and a manifest carrying an error is refused, which took the
  chapter — or the whole group — with it.
- **A manifest that cannot say what it holds.** A `group.json` or `chapter.json` that will not parse,
  names no `id`, or has a `chapters`/`quests` that is not a list of names cannot be read at all, so its
  subtree does not load and the error is against that file. That is the only case where one file costs
  more than itself.

A root `index.json` is a third, and it is the one that is read *around* rather than skipped: one that
cannot declare the root is read as if it were not there, and the tree loads by folder name — see
[[tasked:authoring/quest-files]].

`/tasked progress` prints the tree from the player's point of view: each quest's state, and every task
with its recorded count against what it needs. A task that shows `0/8` while the player carries eight
logs is a matching or consumption question, not a loading one.

> [!NOTE]
> Problems are reported, not enforced on old files: a warning never stops a file from loading, and
> nothing here renames or rewrites your files. The editor's writes are the only path that changes a
> quest file, and only when a player with permission asks it to.
