# The HUD

Tenet draws three things outside its own screens: the quest book's button over your inventory, the quests
you have pinned, and the notices that tell you something just happened. All three are yours to place —
switch them off, move them, or put them back where they shipped — in the **HUD editor**, which opens on
`H` or from the book's Settings card.

The HUD is drawn over the world and over nothing else. It has no buttons and nothing on it can be clicked:
it is a thing to read while you play, and the book — one key away — is where you act.

## Pinning a quest

Two ways in, and they do the same thing:

- **Right-click a quest on the canvas.** `Pin to HUD` adds it. A quest already pinned offers `Focus on HUD`,
  which brings it to the front, and `Unpin from HUD`. With several quests selected, the rows act on all of
  them at once.
- **Open a quest and press the star** in its header, next to the back arrow. A filled star is pinned, a
  hollow one is not.

**At most six quests are pinned at once.** A seventh is refused with a message rather than quietly pushing
one of yours off the list: nothing you pinned goes away unless you unpin it, and `pinned.json` is a text file
you can edit.

Pins are **yours, not the world's.** They live in this client's own config, they survive leaving a server,
and a pin naming a quest the server you are on does not have is simply skipped — it stays in the list, so it
comes back the next time you are somewhere that has that quest.

## The pinned panel

The panel lists your pins with the **most recently pinned at the top**. That first one is drawn in full —
its name, its chapter, and up to six of its tasks with their counts (`3 / 8`) and a tick on each one that is
done — and every other pin is drawn as its name, in the completion colour once it is finished. A quest with
more than six tasks says how many more there are rather than growing past the bottom of your window.

That is the whole of how the panel folds, and it is deliberate: a surface that cannot be clicked cannot have
a twisty on it. To read a different pin's tasks, bring it to the front — pin it again from the canvas, or
`Focus on HUD` — and the one you were reading becomes a name.

A quest with nothing pinned yet draws nothing at all, so switching the element on costs you nothing until
you use it.

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
| **Move** | Brings the element to your pointer; the next press puts it down. `Escape` first puts it back. |
| **Reset** | That one element back to where it shipped, switched on. |
| **Done** | Closes the editor — and comes back to the book, if that is where you opened it from. |

You can also just drag an element. Every position is a pair of pixels in your window, so it means the same
thing in the editor and in the game at any window size; nothing travels with anything else, and a position
that would land off the window is pulled back onto it as you drop it.

The elements are:

| Element | Where it ships | What it is |
|---|---|---|
| **Inventory button** | Top-left, over your inventory | The quest book's other door. Shown on your own inventory in survival *and* creative, and not on containers. |
| **Pinned quests** | Top-left of the HUD | The panel described above. |
| **Notifications** | Just under it | The notices described above. |

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

`pinned.json` holds your pins, in the order they are drawn — the first is the one shown in full:

```json
{ "pins": ["chapter_one/gather_wood", "chapter_one/craft_a_table"] }
```

A pin whose quest is not in the pack you are playing is skipped and kept. The next time the pack moves, so
does the list: ids the tree no longer holds are dropped, and the ones you pinned stay in your order.
