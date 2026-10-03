# KubeJS scripting

A questline can be driven from a KubeJS script: stages, quest state, completions, custom [[task]] and
[[reward]] handlers, and the six lifecycle events. The integration is **NeoForge only**, and that is
KubeJS's doing rather than a choice — KubeJS 7, the 1.21 line, publishes no Fabric artifact. The
operations themselves live in `TaskedScripts`, which names no KubeJS type, so the Fabric build carries
the same Java API for addons; only the script binding is NeoForge's.

KubeJS is optional. Nothing requires it to build, to launch, or to play, and the mod does nothing
script-shaped when it is absent.

## The `Tasked` binding

A server script sees one object, `Tasked`. Every method is one call over the same service the commands
use, so a script cannot do what an operator could not.

| Method | Answers |
|---|---|
| `Tasked.hasStage(player, 'my_pack:inducted')` | whether this player has the stage |
| `Tasked.addStage(player, 'my_pack:inducted')` | whether anything changed — false when they already had it |
| `Tasked.removeStage(player, 'my_pack:inducted')` | whether it was there to take |
| `Tasked.stages(player)` | every stage they have, as strings, sorted |
| `Tasked.state(player, 'quest_id')` | `LOCKED`, `UNLOCKED`, `STARTED`, `COMPLETED`, or `UNKNOWN` for an id that resolves to no quest |
| `Tasked.complete(player, 'quest_id')` | finishes it exactly as `/tasked complete` does — the same service call, so a script cannot complete what the command would refuse |
| `Tasked.customTaskIds()` | the ids this build has a custom-task handler for |
| `Tasked.customRewardIds()` | the same for custom rewards |
| `Tasked.registerTask(id, handler)` | registers what a `tasked:custom` task measures |
| `Tasked.registerReward(id, handler)` | registers what a `tasked:custom` reward does |

There are no permission checks on these, deliberately: the person who wrote the script owns the
questline, and a permission check would be asking them for their own permission. A stage id that does
not parse, or a player who is null, gets a plain `false` or `UNKNOWN` rather than an error thrown into
the script.

`Tasked.state` answers from stored progress — the team's — so a quest gated on a stage this player
lacks still reads `UNLOCKED` there: the gate is a per-player overlay applied when that player's view
is sent. Ask `Tasked.hasStage` for the fact the gate reads.

## Events

`TaskedEvents` is a server event group with one entry per lifecycle moment. Every listener is a
function, and every event object is a description of what happened — public fields, not getters.

| Event | Fields |
|---|---|
| `TaskedEvents.questStarted` | `player`, `quest`, `title` |
| `TaskedEvents.questCompleted` | `player`, `quest`, `title` |
| `TaskedEvents.taskCompleted` | `player`, `quest`, `taskIndex` |
| `TaskedEvents.rewardClaimed` | `player`, `quest`, `type` |
| `TaskedEvents.stageAdded` | `player`, `stage` |
| `TaskedEvents.stageRemoved` | `player`, `stage` |

## A worked script

```js
// kubejs/server_scripts/tasked.js

// Finishing a quest grants a stage. This is how a pack writes "finishing this opens that chapter"
// when the thing linking the two is not a dependency edge -- a stage gate is per player, and a
// dependency is not.
TaskedEvents.questCompleted(event => {
  if (event.quest === 'the_summons') {
    Tasked.addStage(event.player, 'my_pack:inducted')
  }
})

// Stages have their own events, so a script can watch the flag rather than the quest that set it.
TaskedEvents.stageAdded(event => {
  console.info('[tasked] stage granted: ' + event.stage)
})

// A custom task: the handler answers how far along the player is, counted the way the task's own
// `value` counts. The quest names the id:
//   { "type": "tasked:custom", "id": "my_pack:inducted_check", "value": 1 }
Tasked.registerTask('my_pack:inducted_check', (task, context) => {
  return Tasked.hasStage(context.player, 'my_pack:inducted') ? 1 : 0
})

// A custom reward: the handler does whatever the pack needs when the reward is collected.
Tasked.registerReward('my_pack:announce', (player, context) => {
  console.info('[tasked] reward collected on ' + context.questId)
})
```

Register handlers at the top level of a server script, not inside a listener: the registry is
consulted when a quest file is read, and handlers are forgotten before every script load — so a reload
replaces them rather than stacking them, and a deleted script's handler stops existing.

> [!NOTE]
> A `tasked:custom` task whose handler is not registered is not an error: the quest still loads and the
> [[tasked:validation|validator]] warns that nothing in this build provides the id. That is what a pack
> that ships its handler mod as an optional dependency wants to see.

## What has been verified, and what has not

The binding was run, not assumed. A `:neoforge:runServer` with KubeJS on the run classpath and a script
at `neoforge/run/kubejs/server_scripts/tasked_verify.js` produces, in the server log: KubeJS's own
`Found plugin source tasked`, then the script's lines — `Tasked` is an object, its methods are
callable, `Tasked.state(null, "nothing")` answers `UNKNOWN`, a `TaskedEvents.stageAdded` listener
attaches, and a JS arrow function passed to `registerTask`/`registerReward` converts into a Java
handler and appears in `customTaskIds()`/`customRewardIds()`. The load reports 0 errors and 0
warnings.

What that run cannot prove, said plainly: no player exists at server start, so a handler's body
running against a real quest is the pack's own test, and the client-side look of a stage-gated quest
is on the manual playtest list in `TESTING.md`.
