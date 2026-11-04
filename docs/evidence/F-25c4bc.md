# F-25c4bc — Sealed in with cobbled deepslate and no pickaxe, it punches its own seal out by hand

Date: 2026-11-04
Agent / branch: Claude / claude/e40-ladder-4-1104 (E-40's ladder, rung a)
Build: on top of `7b2766e`; recorded in [validation](../validation.md)
Status: complete (native). Its effect on the ten seeds is in E-40's next evaluation.

## Scope and implementation

User-visible behavior: a player with no pickaxe left punches through cobbled deepslate by hand, as it does stone and
cobblestone. That covers its own night seal, pillar or bridge of cobbled deepslate, the deepest rock it carries. Before,
it punched nothing harder than 3.0. Sealed in for the night with cobbled deepslate, every way up was refused, and so
was the opening of its own seal (F-25c4at), and it stood there.

Found: the most common stuck cause on the ten seeds of `143806e` (`e40a2-143806e`, E-40's iteration 2). 14 of the
run's 94 spells were "Head back up toward home": every way up refused, then the walk home refused too.
- **Sol on 20261030, at 101, 35, 92:** 7 spells, the last 41 game minutes of the run (6 of them so labelled).
  - Its last pickaxe wore out on its stair down for lava, with no sticks or planks left. It sealed itself in for the
    night (its east, west and overhead cells), then waited.
  - From morning: "Climb toward the surface for food: back the way it came" refused at its own seal, then "Head back up
    toward home" refused, and waiting. No by-hand dig was ever chosen.
  - The terrain round it, read offline from a fresh world on the seed (diagnosis only), is stone, granite, dirt and
    coal ore, with no liquid near, so nothing there refuses a dig.
  - The console does not name the block it sealed with. Sol had been down to Y=-54 and dropped surplus cobbled
    deepslate there. Of the blocks it seals with (`SEALING`), cobbled deepslate is the one over the by-hand limit
    (hardness 3.5; the rest are 2.0 or less). With it over its head, the climb is refused in every direction (the cell
    over its head comes first in each), the pillar needs that cell open, and the seal is not opened. The fixture plays
    exactly that.
- **Sol on 31102026, at -3, 64, 163:** the other 6 spells, under the desert's sand, where every way up was refused for
  the sand over its head. That is a different cause and stays open.
- **Sol on 8675309, at -175, -51, 49:** 2 spells, "Gather logs for a crafting table", not looked at yet.

Changed modules/files:

- `companion-server-adapter`: `PortalRunCampaign.handClearable` punches by hand up to `HAND_HARDNESS` = 3.5, so
  cobbled deepslate is punched by hand, about 18 seconds a block. Below that limit nothing changes: deepslate ores
  (4.5), obsidian and the like are still left alone.
- gametest: `NativeTripDownGameTests.atTheFootOfItsStairWithNoPickaxeLeftItOpensItsSealInTheMorningAndClimbsOut`
  (new, registered after `NativeBranchMineGameTests`).

## Acceptance evidence

| Criterion | Check | Result |
| --- | --- | --- |
| At the foot of its stair, sealed in with cobbled deepslate and no pickaxe, it gets out in the morning | the fixture. Staged: a stair dug down through stone a step a block, three high a step, from open ground to a foot 13 down, with the two cells dug on beyond the foot; night; the player at the foot, health 8 and food 5, with cobbled deepslate and torches, no pickaxe, no wood and nothing to eat; daylight held once it has sealed itself in. Passes once it has been 6 or more above the foot. | On `7b2766e`: fails 3 of 3. It sealed itself in with cobbled deepslate and was at the foot 5,959, 5,963 and 5,966 ticks after daylight, "waiting for a feasible step", the watchdog's walk elsewhere refused. With the fix: passes 3 of 3, 6 above the foot 2,758, 2,760 and 2,763 ticks after daylight. Each time it punched the seal over its head, pillared a block and dug a stair up by hand |
| A first form of the fixture, not kept | the same scene with cobblestone for the seal | On `7b2766e` it punched the seal over its head and dug up by hand at once, so it reproduced nothing of Sol's. It did show another fault, not looked at yet: one below the top, its "Explore east for logs" walk took it back down its stair to the foot |
| The goal's other fixtures | see [validation](../validation.md) | see there |

Environment: Linux x64 cloud container, OpenJDK 21, the manifest's versions. Staged, as the fixture says.

## Remaining limitations and handoff

- Out by hand is slow. In the fixture it punched a fresh stair through stone rather than going back up its own. The
  fixture has no trail of the way down (it did not walk down), and the seal on its own stair's next step is left
  standing.
- Under sand (Sol on 31102026) the way up is still refused for the sand over its head. It is a separate cause.
- The wood for the trip down is still not kept against the pickaxes it will wear out.
