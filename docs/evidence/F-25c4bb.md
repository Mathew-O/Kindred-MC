# F-25c4bb — On a cavern's rim, the way over the drop is not dug into, and it climbs away

Date: 2026-11-04
Agent / branch: Claude / claude/e40-ladder-2-1104 (E-40's ladder, rung a)
Build: on top of `79f2a3e`; recorded in [validation](../validation.md)
Status: complete (native). Its effect on the ten seeds is in E-40's next evaluation.

## Scope and implementation

User-visible behavior: a player whose tunnel comes out on the rim of a cavern no longer digs out the rock beside it on the sides that drop away. It climbs a step to find another way, back up its own stair or into the rock, and digs on for what it wants. Before, it dug the crust out on every side, found a drop under each, and waited where it stood ("waiting for a feasible step"), with no step left to climb.

Found: the most common cause of stuck spells on the ten seeds of `e9d33c0` (`e40a1-e9d33c0`, E-40's iteration 1).
- **Kindred on 505, at -42, 4, -3:** 32 of the run's 155 stuck spells, in 97 game minutes.
- **The terrain:** its branch mine had stepped down onto the crust over a cavern about 34 deep. From the seed's own terrain, read offline from a fresh world on the seed (diagnosis only), there is air from Y=3 down to an aquifer at Y=-30.
- **What it did there:** it walked 73 times for cave floors it saw on the cavern's walls, each walk refused ("No walkable route"). In between the plan waited, 105 times.
- **Why nothing else worked:**
  - `tunnel` clears the cells of a way before it looks at the floor beyond them. That order is right for a one-block step down, or a pit of its own it means to fill.
  - On a rim it dug the crust out on three sides.
  - With the floor gone under each of those cells, the climb back (`climb`, the plan's last way on underground) had no step left to stand on.
  - The step back up into its own tunnel needs head room over it, so the tunnel was no walking route either.

Changed modules/files:

- `companion-server-adapter`: `PortalRunCampaign.tunnel` skips a way whose floor ahead drops two or more, unless the tunnel bridges drops (the lava's, F-25c4ak) or the drop is a pit it dug itself. A one-block step down and the fill of its own pit are as before.
- gametest: `NativeBranchMineGameTests.onTheRimOfACavernItsBranchMineCameOutOnItGetsAwayFromTheEdgeAndDigsOn` (new).

## Acceptance evidence

| Criterion | Check | Result |
| --- | --- | --- |
| On the rim of a cavern, away from the edge and digging, not waiting | the fixture: a cavern 22 deep, lit so nothing spawns in it, open to the west at the player's level; a crust one block thick over it on the other sides; the player on the edge cell on a pillar of the cavern's rock; its own tunnel a step up behind it; its kit as though made by the iron stage (all staged, as the fixture says) | On `79f2a3e`: fails 3 of 3. "Waiting on the rim", 3,823, 3,818 and 3,821 ticks of "waiting for a feasible step", the crust beside it dug out first. With the fix: passes 3 of 3, back up a step ("Climb to find another way") and branch mining away from the rim after 260, 292 and 261 ticks, never waiting |
| The goal's other fixtures that tunnel | see [validation](../validation.md) | see there |

Environment: Linux x64 cloud container, OpenJDK 21, the manifest's versions. Staged, as the fixture says.

## Remaining limitations and handoff

- A drop it cannot step down or bridge is now never dug toward. A tunnel across a ravine's floor stops at the edge and turns.
- The cave floors on the far walls are still walked for when seen. Each refused walk is quick, and the climb now gets its turn after them.
