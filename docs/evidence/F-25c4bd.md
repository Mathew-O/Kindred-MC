# F-25c4bd — The block over its head not to be dug (sand on it, water beside it): out from the cell beside it

Date: 2026-11-04
Agent / branch: Claude / claude/e40-ladder-4-1104 (E-40's ladder, rung a)
Build: on top of `0467a5f` (F-25c4bc); recorded in [validation](../validation.md)
Status: complete (native). Its effect on the ten seeds is in E-40's next evaluation.

## Scope and implementation

User-visible behavior: a player climbing out whose head room it may not dig no longer stands there for good. The head
room may be a block with sand or gravel resting on it, which would come down onto the player, or a block with a liquid
beside it, which would be let in. The player gets out as a player does, from the cell beside it:
1. It digs a pocket two high beside it, on solid ground, where the pocket's own head room has no liquid beside it.
2. Under sand, it opens the pocket's ceiling from where it stands, so what rested there comes down into the pocket and
   not onto the player.
3. It digs what came down out from beside it, the lowest first, until nothing rests over the pocket.
4. It steps into the pocket and climbs from there.

Before, every way up was refused for that one block: each climb's step needs it, and so does the pillar.

Found: the most common stuck cause on the ten seeds of `143806e` (`e40a2-143806e`, E-40's iteration 2), a player out of
sticks with no pickaxe climbing out of a shaft of its own one wide.
- **Sol on 31102026, at -3, 64, 163 (6 spells to the end of the run, "Gather logs for sticks - Head back up toward
  home"):**
  - It pillared and punched its way up from Y=52 to 64.
  - The terrain, read offline from a fresh world on the seed (diagnosis only): sandstone up to Y=66, sand from 67 to
    70, open air from 71.
  - At 64 the block over its head was sandstone with sand on it.
- **Kindred on 101, at -252, 53, -132 (16 spells, from tick 25,180 to the end of the run, "waiting for a feasible
  step"):**
  - It pillared and punched its way up from Y=48 to 53.
  - The terrain, read the same way: stone over its head to Y=60, then gravel, dirt and grass to the surface at 68.
  - Beside the block over its head (55) is a pocket of three water sources, at -252, 55 to 57, -131.
- Together with Sol's seal on 20261030 (F-25c4bc, 7 spells), these were 29 of the run's 94 spells.

Changed modules/files:

- `companion-server-adapter`:
  - `PortalRunCampaign.climb` notes when the block over its head is refused for sand or gravel on it, or for a liquid
    beside it (`headRoomRefused`).
  - With nothing else open, it then turns to `fromBeside`, which takes the first of these that applies:
    1. into a pocket beside it whose head room it may dig, or which is clear;
    2. the fallen sand in a pocket dug out, the lowest first;
    3. a pocket's ceiling opened from beside it, when sand rests on it;
    4. a pocket dug on solid ground, its head-level cell first, not where the pocket's head room would have a liquid
       beside it.
  - It waits while anything is still falling beside it.
  - Every dig is beside it, never over its own head. Each is made with a tool fit for the block, else by hand, and none
    beside a liquid.
- gametest, `NativeTripDownGameTests`:
  - `inAShaftUnderSandstoneWithSandOnItAndNoPickaxeItGetsOutFromBesideAndClimbs`;
  - `inAShaftWithWaterBesideTheBlockOverItsHeadAndNoPickaxeItGetsOutFromBesideAndClimbs`.

## Acceptance evidence

| Criterion | Check | Result |
| --- | --- | --- |
| Under sandstone with sand on it, no pickaxe: out on the surface, unhurt | the sand fixture, staged: a block of sandstone with four sand on top and open sky over it; a pocket two high in the sandstone, its ceiling a block of sandstone under the sand; daylight held; the player in the pocket, fed, with cobblestone and bread, no pickaxe and no wood. Passes when it stands on the sand at the top with health 18 or more. | On `0467a5f`: fails 3 of 3, still where it stood 5,991 to 5,994 ticks on, "waiting for a feasible step". With the fix: passes 3 of 3 (in two runs of the class), out on the sand after 1,371 to 1,399 ticks, unhurt. Each time it dug a pocket beside it by hand, opened its ceiling, dug out the four sand that came down, stepped in and climbed |
| Stone with water beside it, no pickaxe: out on the surface, unhurt, the water where it was | the water fixture, staged: a block of stone with open sky over it; a pocket two high in it; three water sources in a column beside the block over its head, walled in by stone; the rest as above. Passes when it stands on the top with health 18 or more and no cell but the three sources wet. | With the sand half of the fix only: fails 3 of 3, still where it stood 5,988 to 5,994 ticks on, "waiting for a feasible step". With the fix: passes 3 of 3, out on top after 2,892 to 2,900 ticks, the water not let out |
| The goal's other fixtures | see [validation](../validation.md) | see there |

Environment: Linux x64 cloud container, OpenJDK 21, the manifest's versions. Staged, as the fixtures say.

## Remaining limitations and handoff

- The pocket is dug on the first side, in its dig direction, with solid ground under it and no liquid beside its head
  room. With none such, it is still stuck.
- With water over the sand (a lake's floor of gravel), the way out under the water (F-25c4ah) comes first, as before.
