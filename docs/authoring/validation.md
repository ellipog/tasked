# Validation

A problem in a quest file is cheap to make and expensive to find — a typo'd field name, a dependency
that points at a quest that was renamed, a cycle nobody can unlock. Tasked's answer is to read the
files before anything tries to trust them, and to say what is wrong in the format a compiler uses:

```
quests/getting_started/first_steps/punch_a_tree.json:14:9: error: unknown field "titl" (did you mean "title"?)
```

File, line, column, severity, message. That is `DataProblem.render()`, and it is what the log carries
for every problem a reload finds.

## Two passes, because they catch different mistakes

**The per-file [[validator]] runs before the codecs do.** It checks structure and values: every
required field present, every field one this type actually takes, every enum name valid, every id
well-formed, every item that exists. Codecs alone cannot do this job — they ignore a field they do not
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

> [!TIP]
> `/tasked types` prints every task, reward and [[tasked:authoring/conditions|condition]] type this
> build has, and the field names each one takes — the fastest way to check a field name without
> opening a schema, and the list the validator itself uses.

## What happens when a file is wrong

The file is **skipped, not fatal**. The other files still load, so a pack with one broken chapter
opens on the rest while its author fixes the one. The reload reports the tally:

```cmd
/tasked reload
```

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

`/tasked progress` prints the tree from the player's point of view: each quest's state, and every task
with its recorded count against what it needs. A task that shows `0/8` while the player carries eight
logs is a matching or consumption question, not a loading one.

> [!NOTE]
> Problems are reported, not enforced on old files: a warning never stops a file from loading, and
> nothing here renames or rewrites your files. The editor's writes are the only path that changes a
> quest file, and only when a player with permission asks it to.
