# F-25c4bf — On a ledge below an aquifer's wall, every step up beside the water: it pillars up beside the wall onto its top

Date: 2026-11-05
Agent / branch: Claude / claude/e40-ladder-7-1105 (E-40's ladder, rung a)
Build: on top of `6effd66` (main with F-25c4be and E-40's iteration 4); recorded in [validation](../validation.md)
Status: complete (native). Its effect on the ten seeds is in E-40's next evaluation.

## Scope and implementation

User-visible behavior: a player on a ledge under a wall it may not climb, for the water behind the wall, now pillars up
beside the wall on blocks of its own and steps onto the wall's top, as a player does. Before, every step up was refused,
and it stood there or went to and fro between two cells.

Found: the third most common stuck place in E-40's iteration 4 (`e40a4-c237a6e`). Kindred on 303 had 27 spells at 126 to
127, 11, 40 to 42, from tick 25,000 or so to the end of the run, at stone tools.
- **The terrain**, read offline from a fresh world on the seed (diagnosis only):
  - the lake of an aquifer, its top at Y=13;
  - a wall of stone one to two thick between the lake and a cave;
  - the cave's floor at Y=-55, 65 below the ledge.
- **How it got there:** Kindred swam the lake's top looking for iron, came onto the wall at 123, 15, 38, and went down its
  cave side onto a ledge.
- **What it did there:**
  - Its branch mine dug two cells into the wall, and its climb three by its side, from 126, 11, 41 to 127, 13, 42.
  - Every step back up was beside the lake's water, so each was refused.
  - The cell it had dug into the wall had its head room refused for the same water. It stepped aside out of it (F-25c4bd's
    `fromBeside`), and from there walked "to the wall" back into it, 1,466 and 1,462 times.

Changed modules/files:

- `companion-server-adapter`:
  - `PortalRunCampaign.climb` notes each way whose step up was refused for a liquid beside the landing (`besideWater`).
  - With every way refused and nothing else open, it turns to `pillarBesideTheWater` before its walk "to the wall".
  - `pillarBesideTheWater` looks one to three blocks up, open over its head, for a step onto the same wall whose landing
    is clear of the liquid. With one found, it pillars one block, on the ground and not swimming, with a block it builds
    with. The climb then takes the step up as usual.
  - A pillar beside the water refused twice from one cell within a minute is not asked for from there again in that
    minute (`pillarMisses`).
  - `NativePillarAction` steps to the middle of its cell, and stands still, before it jumps, for at most 20 ticks. Off
    the middle by a wall, the player's head met the block over the cell beside it and the jump did not rise. In the
    fixture's first runs with the fix, one cell refused 151 jumps in a row so. This is every pillar's skill, not only
    this one's.
- gametest:
  - `NativeTripDownGameTests.downOffAnAquifersWallOntoALedgeEveryStepUpBesideTheWaterItPillarsUpAndStepsOntoTheWall`;
  - its structure, `aquifer-ledge-303.snbt`.

## Acceptance evidence

| Criterion | Check | Result |
| --- | --- | --- |
| On the ledge below the aquifer's wall: up onto the wall, three or more above the ledge, on ground and dry | the fixture. Staged: the 21 block cube round the ledge from a fresh world on 303 (x 114 to 134, y 2 to 22, z 28 to 48, read offline); the five cells Kindred dug opened (from its console); the lake's water on the region's sides and floor made stone, so it does not spill out; the cave's floor, 65 below in the world, stood for by lava on the region's floor nine below, so no way down is survivable; daylight held; the player at 126, 11, 40, where it went to and fro, with the iron stage's kit: a stone pickaxe, sticks, planks, a crafting table, cobblestone, torches and bread. Passes once it is on ground, dry, three or more above the ledge. | On `6effd66`: fails 3 of 3, still where it stood 5,991 to 5,994 ticks on, "waiting for a feasible step", after one or two walks "to the wall". With the fix: passes 3 of 3, up on the wall after 194, 187 and 236 ticks, no pillar refused. Each time it pillared twice beside the wall and stepped up onto its top. Before the pillar skill stepped to the middle of its cell, two of six runs took 3,801 and 3,811 ticks, one cell refusing 151 jumps in a row |
| The class's other fixtures, with the fix | `NativeTripDownGameTests` | 5 of 5 in each of the three runs. The lava island (F-25c4be), the other pillar there, was up its hole after 80 to 88 ticks |
| Every other fixture, the pillar's other users among them | the full native suite, see [validation](../validation.md) | see there |

Environment: Linux x64 cloud container, OpenJDK 21, the manifest's versions. Staged, as the fixture says.

## Remaining limitations and handoff

- Only up to three blocks: a wall whose top is higher, or a ledge with no room over its head, is not climbed so.
- The pillar is made beside the wall, over the drop. A fall from it is as dangerous as the ledge.
- A GameTest structure is placed one above the test's origin, so a cell of the structure's file at y is the test's y + 1.
  The fixture allows for it.
