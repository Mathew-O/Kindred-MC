# F-25c4ao — Out from under sand: the block over each step's head dug first, and the climb held to the top

Date: 2026-10-31
Agent / branch: Claude / claude/loose-ends-1029
Build: on top of `91c1290`, the long-ago fix on top of `3c184b6`; recorded in [validation](../validation.md)
Status: in progress (native passed; live runs pending); F-25c4 stays open

## Scope and implementation

User-visible behavior: a player climbing out from under sand or gravel, such as a desert's sandstone with sand over it, now digs out before each step up. From where it stands it digs the block that would be over its head once it is up there. The sand resting on that block falls into the step's empty cells, not onto the player, and is dug in turn. It waits a moment for whatever is still coming down before it steps. In a hole it cannot step out of, open to the sky, it climbs rather than walks off to look for logs or animals. Once climbing for the surface, it keeps on while it is still below the rim of where it climbs.

Before, it stepped up into a cell whose head room had sand resting on it. It never digs that block from under it, so every way up was refused there, and the climb's walk over to a wall dropped it back down. In its first forms the fix stepped up before the sand had fallen and was buried. Then, with sand still falling beside its shaft, the check for being below ground flickered, and it walked from the foot of the shaft into a pocket under the sand.

Found in the live pair on `91c1290`, seed 31102026, a desert spawn, strategist off: Kindred, with no pickaxe, was in a sandstone pocket at -71, 71, 154 with sand over the sandstone. For minutes at a time between its watchdog's spells its plan went between "Climb toward the surface for logs: step up", "to the wall" and "Head back up toward home". The save, rebuilt as it stood then, showed sand resting on the sandstone over each cell a step up from its own.

Changed modules/files:
- `PortalRunCampaign.climb`: before a step up, the block over the landing's head room is dug when a falling block rests on it (or the direction is refused if it cannot be). The step waits while one is still over the open cells (two seconds at most, as sand left hanging over a cave never comes down) or while a falling block is in the column.
- `PortalRunCampaign` (the log and animal searches): in a hole it cannot step out of (`inPit`), and for ten seconds after a climb step while still below the rim (`climbHeld`), it climbs.
- `PortalRunCampaign` (the holds' "long ago"): the climb's and the water step's last-climb ticks start at half the least long, not the least long, which a tick less it overflowed, so each hold held before any climb was made, wherever ground beside the player rose two blocks (F-25c4ah's since `238ff1e`, this one's since `3c184b6`).
- `NativePortalCampaignGameTests`: two fixtures.

## Acceptance evidence

| Criterion | Check or scenario | Result | Evidence |
| --- | --- | --- | --- |
| No pickaxe, wanting logs, in a pocket under sandstone (three high in its middle, a cell two high a block up on either side), three layers of sand over the sandstone and open air over that: it climbs out, unhurt and never buried | `withNoPickaxeUnderSandstoneAndSandItDigsTheSandDownAheadOfEachStepAndClimbsOut` | Before, the change set aside, it fails 3 of 3: "Not out: at 1, 1, 0, waiting for a feasible step". In its first two forms it failed after too, as told above: the first run of each set, and the runs after it in the same set then met that run's player, left running. After, 3 of 3 alone | native |
| No wood, on open ground at the foot of a wall two blocks high, no climb made yet: its first step is to look for trees, not a climb | `atTheFootOfAWallOnOpenGroundItsFirstStepIsToLookForTreesNotToClimb` | Before, on `3c184b6`'s "long ago", it fails 3 of 3: "Its first step a climb on open ground: ... Climb toward the surface for logs: clear ... by hand". After, 3 of 3 alone | native |
| The climbs beside it unchanged | the gravel over its head (F-25c4g), the flooded pit, under a lake, the chamber, the ledge, the pit with no pickaxe, the watchdog's fixture and lava across a cave, alone on the final code | 8 of 8 | native |
| In play | live runs on this code | Pending | live |

Environment: Linux x64 cloud container (4 cores), OpenJDK 21, Minecraft 1.21.1, Fabric Loader 0.19.5, Fabric API 0.116.17.
Fixtures: the sandstone, the sand, the pocket and the torch are staged; the bread and the sword are given.
Analysis: `rebuild.py` in the session's scratch space (not committed) read the save's blocks and undid the logged edits.

## Remaining limitations and handoff

- A player already standing under a sand-loaded block (put there by a walk) still cannot climb from that cell; it climbs once it is off it.
- Sand hanging over a cave by the world's making is waited on for two seconds, then stepped under.
