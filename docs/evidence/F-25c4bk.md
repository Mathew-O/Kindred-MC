# F-25c4bk — A mob attacking and its inventory full: it fights back rather than ask again and again to drop its surplus

Date: 2026-11-05
Agent / branch: Claude / claude/e40-ladder-9-1105 (E-40's ladder, rung a)
Build: on top of `f4bce7a` (main with batch 8); recorded in [validation](../validation.md)
Status: complete (native). Its effect on the ten seeds is in E-40's next evaluation.

## Scope and implementation

User-visible behaviour: a player with a full inventory, attacked by a mob, now fights back or backs off as it would with
room in its pack. It throws its surplus out once the attack is over.

Before, the plan asked for the drop before it looked at the fight. The skill refused every drop while a mob attacked
("A hostile mob is attacking; fight back first"), and the plan asked for it again on its next turn. The fight was never
given.

Found: E-40's iteration 6 (`e40a6-cb4a545`), seed 404, three deaths:
- Echo, minute 61.3, slain by a zombie. Its drop of surplus was refused 17 times running in its last seconds.
- Kindred, minute 73.3, slain by a zombie while dropping surplus cobblestone.
- Sol, minute 69.7, fell from a high place while dropping surplus dirt.

Changed modules/files:

- `companion-server-adapter`: `PortalRunCampaign` asks `makeRoom` for a drop only when no hostile mob is after the
  player and no creeper is closing in. These are the same two checks that make the skill refuse the drop.
- gametest: the replay `echo-73670` (`replays/echo-73670.json`, `structure/replays/echo-73670.snbt`, 749 KB), Echo's
  death on 404, captured by the run (F-27). `ReplayGameTests` runs it three times.

## Acceptance evidence

| Criterion | Check | Result |
| --- | --- | --- |
| Echo's death on 404 played again: it survives | `ReplayGameTests`, the replay `echo-73670`. Staged by definition: the ground, the mobs and the player's state as the run left them. | Code as on `f4bce7a`: died again in 6 of 6 replays, two runs of three, each "Echo was slain by Zombie" (a third run did not compile, a build of mine racing it). With the fix and F-25c4bl: survives in 9 of 9, three runs of three. With F-25c4bk alone, one replay of three was stopped for reconciliation filling a bucket with its inventory full (F-25c4bl). |
| The suite's other replay | `ReplayGameTests`, `echo-50149` | passes in every run, before and after |

Environment: Linux x64 cloud container, OpenJDK 21, the manifest's versions.

## Remaining limitations and handoff

- Only the plan's own drop gives way. A full inventory still cannot take a mob's drops during the fight.
