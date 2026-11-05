# F-25c4bg — A lake's bed of sand over its climb: the block the sand rests on is not dug out

Date: 2026-11-05
Agent / branch: Claude / claude/e40-ladder-7-1105 (E-40's ladder, rung a)
Build: on top of `213f33e` (F-25c4bf); recorded in [validation](../validation.md)
Status: complete (native). Its effect on the ten seeds is in E-40's next evaluation.

## Scope and implementation

User-visible behavior: a player climbing or digging under a lake whose bed is sand or gravel no longer digs out the
block the sand rests on. That would let the sand down and the lake after it. It treats the block as it treats one with
water beside it, and climbs or digs another way. With water over the column, it goes along under the lake until the
ground over its head is dry (F-25c4ah).

Before, a cell was refused only for a liquid beside it. Sand two or more deep between the cell and the water hid the
lake. One climb rule (F-25c4ao) even dug such a block on purpose, to let the sand down into the step's empty cells.

Found: the water loop of E-40's iterations 3 and 4, "Climb out of the water: to the wall". In iteration 4
(`e40a4-c237a6e`) it was 70 of the run's 194 stuck spells. Of those, 59 began with sand coming down a climb ("let what
falls settle" or "dig out what came down") and then the water:
- Sol on 101, at -281, 42, -51 (9 spells, 807 walks refused);
- Kindred on 202, at 101, 49, 100 (9);
- Sol and Kindred on 404, at 43, 43, 45 and 14, 60, -78 (19, and 11 more "Wait for daylight" in the same water);
- Kindred on 606, at 274, 53, -107 (22).

The other 11 (Sol on 505) began otherwise and stay open under F-25c4ay.

Changed modules/files:

- `companion-server-adapter`:
  - `PortalRunCampaign.liquidNear` also answers true for a block with sand or gravel resting on it, up to 16 high,
    with a liquid over or beside any of the column (`liquidOverColumn`). Every dig, climb and tunnel that refuses a cell
    for a liquid beside it now refuses this one too.
  - `climb`'s note of water over its ceiling reads the top of such a column (`columnTop`), and so does the dig of the
    block over the landing.
- gametest:
  - `NativeTripDownGameTests.climbingUpUnderALakeWhoseBedIsSandItDoesNotLetTheSandAndTheLakeDown`;
  - its structure, `lake-bed-101.snbt`.

## Acceptance evidence

| Criterion | Check | Result |
| --- | --- | --- |
| Climbing under the sea, whose bed is gravel over its next step: up, dry, never a minute in the water | the fixture. Staged: the block of 101's terrain round Sol's stair, from a fresh world on the seed (read offline): x -292 to -270, z -62 to -40, from Y=32 to four over the sea's top at 62. Every cell Sol dug there is opened (from its console), but the block under the gravel and the cell it dug as the water came. The sea round it, wider than a fixture holds, is stood for by the region's sides walled in stone all the way up, so no dry ground is in reach of its search, as none was. Daylight is held. The player stands where Sol stood, at -281, 42, -51, with a stone pickaxe, cobblestone and bread, no wood. Passes once it is three or more above where it stood, on dry ground, never a minute in the water. | On `213f33e`: fails 3 of 3. Each time it dug out the block under the gravel, the gravel and the sea came down, and it swam up and trod water at the top, "up for air" 193 to 231 times, until it drowned. With the fix: passes 3 of 3, up after 1,241, 1,242 and 1,225 ticks, never in the water. It went along under the sea bed (F-25c4ah) and climbed where the ground over its head was dry |
| The class's other fixtures | `NativeTripDownGameTests` | 6 of 6 in each of the three runs with the fix; the aquifer ledge (F-25c4bf) up after 186 ticks each time |
| Every other fixture | the full native suite, see [validation](../validation.md) | see there |

Environment: Linux x64 cloud container, OpenJDK 21, the manifest's versions. Staged, as the fixture says.

## Remaining limitations and handoff

- A column more than 16 high is not followed to its top.
- A lake held up by a block that is not sand or gravel, such as a cave's thin stone ceiling, is still let in when that
  block is dug from beside it.
- Sol on 505 (a dig out of a wall under water) is a different start of the water loop and stays open under F-25c4ay.
