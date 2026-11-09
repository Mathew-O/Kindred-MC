# F-25c4cu — Out from under a lake by hand, the climb keeps to its way out until it is clear of the water's level

Date: 2026-11-09
Agent / branch: Claude / claude/lakebed-memory-1109 (CI fully green, F-25c4), on main `659cc0f`
Build: recorded in [validation](../validation.md)
Status: complete (native). Its CI record shows over the next day's runs.

## Scope and implementation

User-visible behaviour: a player with no pickaxe digging its way out from under a lake by hand keeps digging its way out.
Before, a few blocks out it could turn and walk back under the lake the way it came, again and again.

Found: `NativeLakeBedClimbGameTests.underALakesBedWithNoPickaxeItGetsOutFromUnderTheLake` (F-25c4cq, batch 23) failed on
#80's CI run on 9 November (run 42553361521). The player was still under the lake when its 12,600 ticks ran out.
- Run locally in copies of eight on `feb4f82`, it failed 1 of 8 twice (`lakerepro`, `lakerepro2`). Both failures, and CI's,
  took the same route at every one of the fixture's thousand-tick marks.
- `wayUpBy`, with no pickaxe, puts the climb before the walk back while the player is "under water". That order is F-25c4bu's:
  out from under it first, because the way back leads under the water again. "Under water" meant the climb had seen water
  over its steps within the last 600 ticks.
- Digging by hand, a block of stone takes some seven seconds, so the 600 ticks last about four blocks of digging. A few
  blocks out from under the water, they ran out. The order then turned to the pillar, then the way back, then the climb,
  and the way back led under the lake again.
- In the scratch-logged run (`lakerepro2`, logging never committed), "back the way it came" was chosen 544 times with the
  600 ticks run out, in all eight copies, and never while they held. The copy that failed chose it 91 times at one cell, a
  block from where the water had been over it, at its level. The others got out despite it.

Changed file: `companion-server-adapter` `PortalRunCampaign.wayUpBy`. The player counts as under water while within 24 blocks
of where the water was over it, within the last 600 ticks or still no higher than a block above that level. Once it has climbed
higher, the old window holds: the way back reaches only cells as high as it stands, so it no longer leads under the lake.

## Acceptance evidence

| Criterion | Check | Result |
| --- | --- | --- |
| Out from under the lake by hand, within the fixture's 12,000 ticks, every time | `NativeLakeBedClimbGameTests`' fixture in copies of eight (a scratch class, not committed) | On `feb4f82`, without the fix: 14 of 16, out after 6,516 to 7,628 ticks (median 6,674), and 2 still under the lake at the end. With the fix: 24 of 24, out after 6,683 to 6,875 ticks (median 6,861) |
| No turn back under the lake with the under-water order run out | The same runs, scratch-logged | 544 such choices in eight copies without the fix; none in 24 with it |
| The climb's classes | The 26 classes of batch 23's set | See [validation](../validation.md) |

This is not a forcing fixture. The failure needs the 600 ticks to run out at a step where the way back is open, which falls
on about one run in eight. The evidence is the 16 runs and 24 runs above, on the fixture as it stands.

The benches ran beside E-40's iteration 16, at the lowest priority, on the cores it left idle.

Environment: Linux x64 cloud container, OpenJDK 21, the manifest's versions.

## Remaining limitations and handoff

- A player that climbs more than a block above the water's level and is then held there still has the old 600 ticks. Its
  way back reaches only cells as high as it stands, so it cannot lead under that lake, but another body of water above it is
  not counted.
