# F-25c4au — Walled in with the danger still about, it digs away from it

Date: 2026-10-31
Agent / branch: Claude / claude/loose-ends-1029
Build: on top of `2bceb1f`; recorded in [validation](../validation.md)
Status: in progress (the fixture and its neighbours pass on `2bceb1f`, the fixture failing before; live runs pending); F-25c4 stays open

## Scope and implementation

User-visible behavior: a player below ground that has walled itself in against a mob it is no match for, and finds the mob still after it a minute later, digs away from it. It tunnels on its own level along the way most nearly away from the mob, seals behind it each cell that opens toward the mob, and leaves the way on open. Before, it waited behind its wall for as long as anything it was no match for had it as a target, and a spawner's stream never stops.

Found in the live pair on `00e98f5` (seed 8675309, the End goal, strategist off): Kindred, at the portal stage below ground beside a mineshaft, walled itself in against its cave spiders, about twenty of them from a spawner, with a zombie three blocks off. It waited there from 13:20 to the end of the seed, twenty-two minutes of the sprinted run.

Changed modules/files:
- `PortalRunCampaign.holeUp`: walled in for more than 1,200 ticks (a minute) with the danger still about, `digAwayFrom` tunnels away from it with `tunnelStraight`, on its own level, never along a way toward it. While it digs away, only the openings toward the danger are sealed.
- `NativePortalCampaignGameTests`: one fixture.

## Acceptance evidence

| Criterion | Check or scenario | Result | Evidence |
| --- | --- | --- | --- |
| Walled into a pocket below ground, health 10, nothing to eat, a zombie after it four blocks off beyond the stone: a minute on it digs away from it, at least three blocks, the wall toward it left standing, alive | `walledInWithAMobStillAfterItItDigsAwayFromItRatherThanWaitForEver` | Before (it waits behind the wall for as long as the danger is about), 0 of 3: "Still walled in: at 0, 0, 0, Iron: outmatched - Hole up: wait behind the wall". After, 3 of 3, each alone in its batch (2026-10-31) | native |
| The hole-up beside it unchanged | The wall against a cave it is no match for, and the fight with a mob already in the tunnel rather than a wall | 2 of 2, each alone in its batch, on the final code (2026-10-31) | native |
| In play | live runs on this code | Pending | live |

Environment: Linux x64 cloud container (4 cores), OpenJDK 21, Minecraft 1.21.1, Fabric Loader 0.19.5, Fabric API 0.116.17.
Fixtures: the stone round the pocket and the zombie's cell are staged. The zombie's AI is off, and the fixture keeps its target on the player each tick, as a spawner's stream keeps a mob after it. The stone pickaxe and cobblestone are given, health is set to 10 and food to 4, and it is daytime.

## Remaining limitations and handoff

- It digs only on its own level; walled in with liquid on every way away, it waits as before.
- A minute walled in is a fixed wait; it does not first judge whether the danger is a spawner's stream or a passing mob.
- The tunnel away is sealed behind it a cell at a time, which is slow; a player in a hurry would run.
