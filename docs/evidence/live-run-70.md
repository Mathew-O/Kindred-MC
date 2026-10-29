# Live run 70 — three players from empty inventories (2026-10-29)

Date: 2026-10-29
Agent / branch: Claude / claude/modest-faraday-r3spro
Build: `a1f044f` with the working tree's combat changes of that morning (the duel and melee fixes in [E-41b](E-41b.md), which do not touch the goal's play); a scratch worktree, not the main tree
Status: complete as a run; what it found is listed below and in the features it concerns

## The run

- A fresh world, seed 8675309, on a dedicated server in a scratch worktree (Fabric 1.21.1, OpenJDK 21, Linux x64), no strategist.
- `/aiplayer edits on`, `/aiplayer bases on`, `/aiplayer pets on`, the daylight cycle on.
- Kindred, Sol and Vesp spawned from the roster with empty inventories at the world's spawn. Kindred given the Nether goal; Sol and Vesp set to play survival (`OPTIMAL`) with no goal.
- `tick sprint 200000`, run for 53 minutes of real time at about 55 ticks a second: about 175,000 ticks, seven in-game days.

## What it showed

| Area | What happened | Feature |
| --- | --- | --- |
| Progress from nothing | Kindred went from wood to stone to iron with its own tools. After they were given the goal (below), Sol and Vesp did too, and Vesp was branch-mining for lava to cast the portal's frame when the run was stopped. No portal was lit in the run. | F-25c4 |
| Players with no goal | For six minutes Sol and Vesp, playing with no goal and with bases on, did nothing but back off from mobs and wait. A cabin needs stone tools first, and nothing they do fetches them. On the first night they died to spiders and skeletons five times between them. Given the Nether goal at 00:45 they began to progress at once. | M-01c |
| The team | Sol shared out its iron by what each lacked: "I've got 13 iron ingots: an iron sword and an iron chestplate for me and an iron sword for Kindred." The best-equipped took on the obsidian: "I've got the best pickaxe; I'll dig the obsidian for the portal." | E-40a |
| Holing up | Kindred, underground at four health, hunger 15 and nothing to eat, walled itself in against a zombie and waited behind the wall for over half an hour (00:57 to the run's end at 01:31). The hole-up's outmatched branch had no time limit, and below 18 hunger waiting heals nothing. **Fixed** after the run: hurt, too hungry to heal and with nothing to eat, it no longer holes up by day (it climbs out for food, as it does elsewhere), and no hole-up lasts more than two minutes. | F-25c4 |
| Stuck in a wall | Vesp, digging up toward water, was stuck in a wall. It dug out three times, and each time the block above it was back (as falling gravel or sand refills a hole). It suffocated. Not fixed: open. | F-25c4 |
| Deaths | Seven: five on the first night to spiders and skeletons (Sol and Vesp, with no goal); Kindred to a zombie underground; Vesp suffocated. Each respawned and went on. | F-25c4 |
| Pets and bases | No wolf was tamed: none had bones and none met a wolf. No base was begun: the players with goals put the goal first. | E-37, M-01c |

Commands executed: the scratchpad scripts `live-wt-start.sh` and `live-wt-setup.sh` (the commands above), then `/aiplayer goal nether Sol` and `/aiplayer goal nether Vesp` at 00:45, then `tick sprint stop` and `stop` at 01:31.
Report location: the server log is a scratch artifact, not committed. The lines quoted above are from it.

## Remaining limitations and handoff

- A player with no goal should make what its next base needs (M-01c).
- A block that refills as it is dug out of should be dug around or stepped away from (F-25c4).
- The hole-up fix wants a live run of its own.
- A run long enough to light the portal, with three players.
