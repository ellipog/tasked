# Commands

Everything Tasked can be asked from the command line is under `/tasked`. The commands that change
anything ask for permission level 2 — a command block's level — and the rest are for whoever is
playing.

## For players

| Command | What it does |
|---|---|
| `/tasked quests` | The tree at a glance: how many quests, chapters and chapter groups, then the groups by title. |
| `/tasked quest <id>` | One quest as the engine sees it: location, dependencies, and the flags set on it. |
| `/tasked progress` | The tree from your point of view: each quest's state, and every task's count against what it needs. |
| `/tasked submit <quest> [task <n>]` | Hand a task in by hand — the same call the Submit button makes. `task` is the task's index, counted from 0; omitted, it is the first task. |
| `/tasked claim <quest>` | Collect the quest's outstanding rewards. The same call the Claim button makes. |
| `/tasked stage list [player]` | The stages a player carries. Without an argument, your own. |
| `/tasked party` | Who is in your party, how it counts, and **which mod the parties come from** — Tasked's answer to "are these the parties I think they are". |
| `/tasked version` | The Tasked and Armature versions this server is running. |

## For operators

| Command | What it does |
|---|---|
| `/tasked reload` | Re-read the quest folder without a restart, and re-sync every connected player. Reports how many files loaded, and how many had errors. |
| `/tasked complete <quest>` | Complete a quest for the team, exactly as the engine would. |
| `/tasked reset [quest]` | Clear progress — one quest, or the whole tree when no quest is named. Run it as a player: progress belongs to a team, and the console is not in one. |
| `/tasked rewards block` / `unblock` | Hold or release the team's automatic payouts. |
| `/tasked stage add <player> <stage>` | Grant a stage. |
| `/tasked stage remove <player> <stage>` | Take one away. |
| `/tasked types` | Every task, reward and condition type this build has, with the fields each one takes. |

## Parties

Party membership is stored and shared by Armature's team API ([[armature:api/teams]]), so the parties
Tasked shows may be its own or another mod's, depending on what is installed.

| Command | What it does |
|---|---|
| `/tasked party create <name>` | Form a party; you are its owner. The name must be 1 to 32 characters. |
| `/tasked party invite <player>` | Invite a player. Members may invite while the party's switch says so; officers always may. |
| `/tasked party accept [party]` | Accept an invitation. With an id, that invitation exactly — a player can hold several, and the panel's Accept button names the row it is on. |
| `/tasked party decline <party>` | Turn an invitation down. |
| `/tasked party join <party>` | Join an open party from the solo screen, without an invitation. Refused while you are in another party. |
| `/tasked party rename <name>` | Rename the party. Owner only; the same name rule as `create`. |
| `/tasked party transfer <player>` | Hand ownership to a member. The previous owner becomes an ordinary member. |
| `/tasked party handover <player>` | Give the party away **and leave it**, in one validated operation — what the panel's successor picker sends. |
| `/tasked party uninvite <player>` | Withdraw an invitation before it is answered. Whoever sent it may always withdraw their own. |
| `/tasked party open [on\|off]` | Read, or set, whether anybody may join without an invitation. Owner only. |
| `/tasked party member-invites [on\|off]` | Read, or set, whether ordinary members may invite. Owner only. On by default. |
| `/tasked party leave` | Leave the party you are in. An owner with members present is sent to the successor picker by the panel; by command the ownership fallback applies. |
| `/tasked party kick <player>` | Remove a member. |
| `/tasked party disband` | End the party for everyone in it. |
| `/tasked party mode [mode]` | Read, or set, how the party's counts combine. |

**Leaving keeps what you earned.** When a membership ends — leave, kick or disband — the party's
completed quest nodes are merged into the departing player's own record (union, best-of-both), so a
progression-gated pack cannot soft-lock somebody who did a chain with friends and then went solo. A
reward already collected in the party stays collected; one never claimed stays claimable. Joining a
party still imports nothing.

**A party holds eight by default**, and both the invitation and the answer are refused past the cap —
a party can fill between the two. A server may set `teams.maxMembers` between 2 and 64 in
`config/armature/config.json`; see Armature's teams page for the file and what else is in it.

**On servers whose parties come from another mod**, the commands refuse with the source's name where
their API cannot do the operation: Open Parties and Claims has no rename, transfer or policy, and FTB
Teams is read-only here. `mode` works everywhere, because how a party counts is Tasked's own.

A party's mode decides what "the party has eight logs" means:

| Mode | The party's count is |
|---|---|
| `one_member` | The largest single member's count — one player gathers and both progress. The default. |
| `pooled` | Every member's count added together. A consuming task pays from as many members as it takes. |
| `owner_only` | The owner's count alone, whatever anyone else carries. |

There is one more command, `/tasked echo <times>`, and it is not useful: it is kept because it is the
cheapest proof that a Brigadier argument and a return value survive the trip through Armature's
command event.
