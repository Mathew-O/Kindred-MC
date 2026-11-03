# F-25c4ax — Out of its own flooded shaft, where the way up to air is a block high

Date: 2026-11-03
Agent / branch: Claude / claude/flooded-shaft-1103
Build: on top of `36b4b49`; recorded in [validation](../validation.md)
Status: complete (native). Not yet seen in the open: the next evaluation will say.

## Scope and implementation

User-visible behavior: a player with its head under water, rock straight above it, and no dry ground it can swim to,
looks along the way up through the water to the nearest air. Where that way is only a block high somewhere, too low for
its body, it breaks the block that makes it so, and then swims up and out as before. Before, every walk out found no
route: it swam "to the wall" again and again, or drowned there.

Found: the water livelock of the last two evaluations, on 8675309 (`e25a-7c8febf`: Kindred, 830 walks "to the wall" in
19 minutes, to the end of the run), on 404 and 505 (`social-8537a03`: 1,124 and 557).
- **The 8675309 trace:** climbing toward the surface for food, Kindred dug its stair up into a lake's floor. The sand
  over it came down the shaft ("let what falls settle"), and the lake after it. It stood on its flooded pocket's floor.
  - Over the pocket's top cell was rock. In the shaft's bottom cell beside it lay the last of the sand. The one way up
    through the water to the lake passed between the two, a block high.
  - No dry ground could be swum to; every step up out of the water was refused for the water beside it. Its walk over
    "to the wall", to the water under the hole, found no route ("expanded 6000") each time.
- **On 404** the same: a stair dug up toward water, "let what falls settle", then the water and the same loop.

Changed modules/files:

- `companion-server-adapter`:
  - `PortalRunCampaign`: `lowGapToAir` (the way through the water to the nearest air, as `airThroughTheWater` searches
    it, and the first solid block over a cell of that way); `surface`, which digs that block, before anything else, when
    its head is under and rock is straight above.
- gametest: `NativeFloodedShaftGameTests.inItsOwnShaftFloodedFromALakeAboveItGetsOutOfTheWater` (new class).

## Acceptance evidence

| Criterion | Check | Result |
| --- | --- | --- |
| Out of its flooded shaft, by opening the way up | the fixture, as on 8675309: the pocket and its stair as dug, flooded; the shaft up to a hole in the lake's floor; the sand in the shaft's bottom cell; rock over the pocket; the lake three deep with its shore; a stone pickaxe, dirt and bread. It must be out of the water on its own body (one respawned after drowning fails it) | On main's code: fails 3 of 3, "Drowned in the pocket" after "Climb out of the water: to the wall" found no route. With the fix: passes 3 of 3, "Get out of the water: open the way up at" the rock over the pocket, then up for air and ashore, within 2 seconds of the block breaking |
| The goal's other water fixtures and the rest | `NativePortalCampaignGameTests` (the flooded pit, under a lake, the tree in water, the drownings), `ReplayGameTests` (the drowning replays), `NativeSurvivalGameTests`, `NativeBoatGameTests` and the new class together, with the fix: on main's code, then on top of `36b4b49`; then `NativePortalCampaignGameTests` alone twice | 110 of 110 on main's code. On top of `36b4b49`, 109 of 110: `hurtAndOutOfFoodOnceUpItLooksForAnimalsRatherThanGoBackDown` did not have its beef in time after "Hunt cow". The new rule never fired in it, and the class alone passed 95 of 95 twice after. The rule fired only in the new fixture |

Earlier versions of the fixture, each failing to reproduce on main's code:
- The pocket open to the lake, with no gap: the player swam up the shaft and ashore.
- The lake's sides made sheer: it swam up for air and dug a step out of the side.
- The lake left to pour into a dry pocket: the water ran shallow round its feet.

The third run with the sheer-sided lake and the fix failed too. It came up into the lake and spent its time there on the
lake's sheer sides, not on the gap, so the lake was given its shore back.

Environment: Linux x64 cloud container, OpenJDK 21, the manifest's versions. Staged, as the fixture says: the stone, the
lake, the pocket and shaft as dug and flooded, the sand, daylight and the kit.

## Remaining limitations and handoff

- Only a way up through water is opened, and only where its head is under with rock above. A pocket with no air to swim
  to at all, a closed aquifer say, is left to the rules before.
- What let the water in is not changed: its stair up still digs into a lake's floor when sand hides the water above.
