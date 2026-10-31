# F-25c4am — A step off a hazard goes to the middle of the cell found safe

Date: 2026-10-31
Agent / branch: Claude / claude/loose-ends-1029
Build: on top of `21046b0`; recorded in [validation](../validation.md)
Status: in progress (native passed; live runs pending); F-25c4 stays open

## Scope and implementation

User-visible behavior: a player standing in a hazard steps to the middle of the safe cell next to it, the cell the runtime found safe: off magma, off a lava pool's edge, out of fire. Before, the step went a straight block on from wherever the player stood. Off the middle of its own cell, that could overlap the wall beside the safe cell, and the step was refused. It was asked for again on every tick, and the player's other work was stopped as a hazard meanwhile, so nothing ran.

Found in the live pair on `c432fba` (seed 20261030, strategist off): Echo, digging toward lava at Y=-54, stood at a pool's edge. Its block was over the lava, its feet held by the block beside it. For twenty-seven minutes it started nothing. Its stall line read "decision=PROPOSE_TASK Urgent necessity: need 99.0 … blocker=Retreat needs a clear, supported, loaded non-hazardous goal", while its goal asked for the same step, unrun, and the watchdog's spells came and went. In the world save the cell south of it was solid ground with a wall beyond.

Changed modules/files:
- `NativePlayerSkills` (`native_retreat`): the destination is the middle of the cell that many blocks off from the player's own, at the height of its feet.
- `NativeSurvivalGameTests`: one fixture.

## Acceptance evidence

| Criterion | Check or scenario | Result | Evidence |
| --- | --- | --- | --- |
| At a lava pool's edge, its block over the pool, its feet held by the ledge, a wall a block beyond the ledge: it steps back onto the ledge, unhurt | `offTheMiddleOfItsCellAtALavaPoolsEdgeItStepsBackToTheSolidCell` | Before, the change set aside, it fails 3 of 3: "at 0, 0, 0 over lava, Retreat needs a clear, supported, loaded non-hazardous goal". After, 3 of 3 alone | native |
| The other steps off hazards unchanged | the magma retreat, with edits off, idle, the operator's retreat, the stop that cancels it, and the creeper retreat, alone on this code | 6 of 6 | native |
| In play | live runs on this code | Pending | live |

Environment: Linux x64 cloud container (4 cores), OpenJDK 21, Minecraft 1.21.1, Fabric Loader 0.19.5, Fabric API 0.116.17.
Fixtures: the pool, the ledge, the wall and the player's place on the edge are staged.

## Remaining limitations and handoff

- One safe cell beside it is still needed; a player with none (lava on every side) is not stepped anywhere, as before.
- The goal's own work is still stopped while the hazard lasts; the step is what ends it.
