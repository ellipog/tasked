# The HUD

Tenet draws three things outside its own screens: the quest book's button over your inventory, the quests
you have pinned, and the notices that tell you something just happened. All three are yours to place —
switch them off, move them, or put them back where they shipped — in the **HUD editor**, which opens on
`H` or from the book's Settings card.

The HUD is drawn over the world and over nothing else. It is a thing to read while you play, and the
book — one key away — is where you act. The one exception is the pins: with chat open the cursor is
already free, and a press on a box opens the book on that quest, with the canvas brought to it. The box
under the pointer wears an outline, so a press lands where the ring says it will. Chat
keeps every press that lands nowhere near a pin; a press on one closes chat, so anything typed but
unsent goes with it.

## Pinning a quest

Three ways in, and they all do the same thing:

- **Right-click a quest on the canvas.** `Pin to HUD` adds it; a pinned quest offers `Unpin from HUD`
  instead. This works whether or not you can edit the pack — pinning writes your own file, so there is no
  permission to ask for. With several quests selected in edit mode, the rows act on all of them at once.
- **Open a quest and press the star** in its header, next to the back arrow. A filled star is pinned, a
  hollow one is not.

**At most six quests are pinned at once.** A seventh is refused with a message rather than quietly pushing
one of yours off the list, and `pinned.json` is a text file
you can edit.

A quest you have finished **and collected** leaves the stack on its own: a watched quest with nothing left
to watch is a box nobody asked for. The pin stays — hiding is not unpinning, so it is still in the list and
comes back if anything about the quest changes — and `Hide claimed quests` in the HUD editor switches the
hiding off. A finished quest with rewards still out stays until you collect them.

Pins are **yours, not the world's.** They live in this client's own config, they survive leaving a server,
and a pin naming a quest the server you are on does not have is simply skipped — it stays in the list, so it
comes back the next time you are somewhere that has that quest.

## The pinned boxes

Every pinned quest gets **its own box**, stacked down the left edge in the order you pinned them — most
recent first. Each box shows its quest's name and chapter, then its tasks with their counts (`3 / 8`) and a
tick on each one that is done. A task that is underway grows a 2-pixel bar under its sentence, filling as it
goes and gliding to the new width when progress lands rather than jumping; the unfilled remainder is a grey
track rather than empty world, so a half-done task reads as a bar. A task with nothing done yet
shows none. A quest with more than six tasks says how many more there are
rather than growing past the bottom of your window, and a finished quest says `Complete` in its own box —
or `Claimable`, when its rewards are still out: done is not the news, collectable is.

The book's header has a **Pinned** button, beside Settings, that lists every pin: each row wears its
quest's icon, a press opens its card with the canvas brought to it, and the star on its end lets it go.
Finished and collected quests read dimmed there, which is what tells them from the ones still being
watched. Whether they leave the stack on their own is the **Hide claimed quests** switch in the HUD
editor, on unless you say otherwise.

The boxes are transparent: no fill, just a thin edge, over a dim wash whose strength is yours. The slider
on each drawn element's row in the HUD editor runs 0 to full — 0 is edge-only, full is the theme's own
wash — and the pins ship at half while the notices ship full, which is the look each of them already had.
Text is drawn with its shadow, which is what keeps it readable with no panel behind it.

The stack sits **middle-left** and grows from the middle: pin another quest or finish a task and the boxes
above and below move equally, so the thing you are reading does not slide away. Drag it anywhere in the HUD
editor and it stays where you put it; `Reset` puts it back middle-left.

## The notices

Three things are announced, and each is said once:

| When | What it says |
|---|---|
| A task is finished | `Task done: <the task>` |
| A quest is finished | `Quest complete: <the quest>` |
| A chapter is finished | `Chapter complete: <the chapter>` |

A quest completing also swallows the tasks that finished with it — the last task is what completes a quest,
and one sentence followed by the whole task list is a parade rather than news. A quest whose rewards are set
to be granted silently (`no_toast`) says nothing at all, tasks included; that is the author's answer to "do
not announce this one", and it is the switch to reach for rather than the volume of your own HUD.

**Where a notice appears depends on where you are looking:**

- **The book is open** — it goes to the book's own stack, over the book, because chat cannot be read behind
  a screen.
- **The book is shut, and the `Notifications` element is on** — it draws as a row at that element's place,
  in the panel colours, and fades after a few seconds.
- **The book is shut, and that element is off** — it is a toast in the corner, the way it has always been.

The `Notifications` element ships **on**, so a fresh install reads its notices on the HUD. If you preferred
the toasts, switch it off in the HUD editor and they come back; there is never both at once, so one
completion is never said twice.

## The editor

`H` opens it over the live world, which is the point: every element is drawn where it really is, at the size
it really is, so what you drag is what you see in game.

| | |
|---|---|
| The **switch** on a row | Whether that element is drawn at all. A switched-off element is still drawn *in the editor*, dimmed, or you could never find it again. |
| The **slider** under a drawn element's controls | How strong its background dim is, from none to full. It writes straight to the file as you drag it, like everything else here. |
| **Hide claimed quests**, under the elements | Whether a quest you have finished and collected leaves the pinned stack on its own. On unless you say otherwise; the pin stays either way. |
| Arrow keys | Nudge the selected element a pixel — ten with `Shift` held. For placing something exactly, where a drag always overshoots by one. |
| **Reset** | That one element back to where it shipped, switched on and dimmed as shipped. |
| **Done** | Closes the editor — and comes back to the book, if that is where you opened it from. |

Drag an element to move it. Every position is a pair of pixels in your window, so it means the same
thing in the editor and in the game at any window size; nothing travels with anything else, and a position
that would land off the window is pulled back onto it as you drop it.

The elements are:

| Element | Where it ships | What it is |
|---|---|---|
| **Inventory button** | Top-left, over your inventory | The quest book's other door. Shown on your own inventory in survival *and* creative, and not on containers. |
| **Pinned quests** | Middle-left of the HUD | The boxes described above. |
| **Notifications** | Top-left of the HUD | The notices described above. |

## The two files

Both live in `config/tenet/` and both are safe to hand-edit; the game reads them at startup.

`hud.json` holds **only what you changed**, as a diff from the shipped layout, so a file you have never
touched does not exist and one you have put back is empty:

```json
{
  "elements": {
    "pinned_quests": { "x": 210, "y": 8 },
    "notifications": { "on": false }
  }
}
```

`pinned.json` holds your pins, in the order they are drawn — most recent first:

```json
{ "pins": ["chapter_one/gather_wood", "chapter_one/craft_a_table"] }
```

`hideClaimed` travels with them, written only when you switch it off — it is on unless the file says
`false`:

```json
{ "pins": ["chapter_one/gather_wood"], "hideClaimed": false }
```

A pin whose quest is not in the pack you are playing is skipped and kept. The next time the pack moves, so
does the list: ids the tree no longer holds are dropped, and the ones you pinned stay in your order.
